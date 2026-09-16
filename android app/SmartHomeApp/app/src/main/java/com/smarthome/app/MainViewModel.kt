package com.smarthome.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.smarthome.app.cloud.CloudDeviceManager
import com.smarthome.app.cloud.CloudPollingService
import com.smarthome.app.cloud.NleCloudManager
import com.smarthome.app.cloud.SensorAlertManager
import com.smarthome.app.cloud.SensorPoint
import com.smarthome.app.cloud.ServiceEventBus
import com.smarthome.app.model.ControlSwitch
import com.smarthome.app.model.Rule
import com.smarthome.app.model.RunTimeSlot
import com.smarthome.app.model.SensorData
import com.smarthome.app.model.SensorDeviceItem
import com.smarthome.app.network.ConnectionManager
import com.smarthome.app.repository.CloudRepository
import com.smarthome.app.repository.DataStoreRepository
import com.smarthome.app.repository.LocalRepository
import com.smarthome.app.repository.NotificationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MainApplication
    private val dataStoreRepository: DataStoreRepository = app.dataStoreRepository
    private val cloudRepository: CloudRepository = app.cloudRepository
    private val localRepository: LocalRepository = app.localRepository
    private val notificationRepository: NotificationRepository = app.notificationRepository

    val gson: Gson = dataStoreRepository.gson

    private val cm: ConnectionManager get() = localRepository.connectionManager
    private val cloudManager: NleCloudManager get() = cloudRepository.nleCloudManager
    private val cloudDevMgr: CloudDeviceManager get() = app.cloudDeviceManager
    private val alertMgr by lazy { SensorAlertManager(getApplication()) }

    private var statusPollJob: Job? = null
    private val pendingTimeouts = mutableMapOf<Long, Runnable>()
    private val retryRunnables = mutableMapOf<Long, Runnable>()

    // StateFlow
    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData

    private val _connectionStatus = MutableStateFlow("未连接")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _lightState = MutableStateFlow(false)
    val lightState: StateFlow<Boolean> = _lightState

    private val _fanState = MutableStateFlow(false)
    val fanState: StateFlow<Boolean> = _fanState

    private val _logMessages = MutableStateFlow<MutableList<String>>(mutableListOf())
    val logMessages: StateFlow<MutableList<String>> = _logMessages

    private val _rules = MutableStateFlow<MutableList<Rule>>(mutableListOf())
    val rules: StateFlow<MutableList<Rule>> = _rules

    private val _switches = MutableStateFlow<MutableList<ControlSwitch>>(mutableListOf())
    val switches: StateFlow<MutableList<ControlSwitch>> = _switches

    private val _cloudSensorValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val cloudSensorValues: StateFlow<Map<String, String>> = _cloudSensorValues

    private val _cloudLoginState = MutableStateFlow(false)
    val cloudLoginState: StateFlow<Boolean> = _cloudLoginState

    private val _cloudConnected = MutableStateFlow(false)
    val cloudConnected: StateFlow<Boolean> = _cloudConnected

    private val _sensorDevices = MutableStateFlow<MutableList<SensorDeviceItem>>(mutableListOf())
    val sensorDevices: StateFlow<MutableList<SensorDeviceItem>> = _sensorDevices

    private var cloudDeviceIds: List<Int> = emptyList()
    private var sensorMetaCache: Map<String, SensorPoint> = emptyMap()
    private var savedProjectList: List<com.smarthome.app.cloud.ProjectInfo> = emptyList()
    private var savedProjectId: Int = -1
    private var savedProjectName: String = ""
    private var savedDeviceList: List<com.smarthome.app.cloud.DeviceBaseInfo> = emptyList()

    companion object {
        var sensorMetaCacheStatic: Map<String, SensorPoint> = emptyMap()
            private set
    }

    private val mainHandler = object : Handler(Looper.getMainLooper()) {
        @Deprecated("Deprecated in Java")
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ConnectionManager.MSG_CONNECTED -> {
                    val text = msg.obj as String
                    _connectionStatus.value = text
                    addLog(text)
                    syncAllPendingRules()
                    startStatusPolling()
                }
                ConnectionManager.MSG_DISCONNECTED -> {
                    _connectionStatus.value = "未连接"
                    addLog(msg.obj as String)
                    stopStatusPolling()
                }
                ConnectionManager.MSG_SENSOR_DATA -> {
                    val data = msg.obj as SensorData
                    _sensorData.value = data
                    notificationRepository.checkAlerts(data)
                    checkRules(data)
                }
                ConnectionManager.MSG_CONTROL_ACK -> {
                    val json = msg.obj as String
                    if (json.startsWith("STATUS:")) {
                        parseAndApplyStatus(json)
                        addLog("状态更新: $json")
                    } else {
                        handleControlAck(json)
                        addLog("收到确认: $json")
                    }
                }
                ConnectionManager.MSG_RULE_ACK -> handleRuleAck(msg.obj as String)
                ConnectionManager.MSG_ERROR -> {
                    addLog("错误: ${msg.obj}")
                    _connectionStatus.value = "错误: ${msg.obj}"
                }
                ConnectionManager.MSG_LOG -> {
                    val text = msg.obj as String
                    if (text.startsWith("STATUS:")) parseAndApplyStatus(text)
                    addLog(text)
                }
            }
        }
    }

    init {
        localRepository.setHandler(mainHandler)
        loadRules()
        loadSwitches()
        if (!dataStoreRepository.isSensorCacheCleared()) {
            dataStoreRepository.clearSensorCache()
        }
        loadSensorDevices()
        collectServiceEvents()
    }

    fun startAutoLogin() {
        autoLogin()
    }

    override fun onCleared() {
        super.onCleared()
        statusPollJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        localRepository.disconnect()
    }

    fun correctSwitchState(index: Int) {
        val list = _switches.value ?: return
        if (index !in list.indices) return
        val sw = list[index]
        val newList = list.toMutableList()
        newList[index] = sw.copy(isOn = !sw.isOn)
        _switches.value = newList
        saveSwitches()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            ServiceEventBus.sensorUpdate.collect { sensors ->
                val onlineDeviceIds = savedDeviceList.filter { it.IsOnline }.map { it.DeviceID }.toSet()
                val filterByOnline = savedDeviceList.isNotEmpty()
                val values = mutableMapOf<String, String>()
                for (s in sensors) {
                    val key = "${s.DeviceID}:${s.ApiTag}"
                    if (!filterByOnline || s.DeviceID in onlineDeviceIds) {
                        values[key] = s.Value
                    } else {
                        values[key] = "--"
                    }
                }
                _cloudSensorValues.value = values
                updateSensorDataFromCloud(sensors)
                updateActuatorStatesFromCloud(sensors)
                notificationRepository.checkCloudSensorAlerts(values)
            }
        }
        viewModelScope.launch {
            ServiceEventBus.strategySync.collect { strategies ->
                syncStrategyStates(strategies)
            }
        }
        viewModelScope.launch {
            ServiceEventBus.deviceOnlineStatus.collect { statusMap ->
                if (savedDeviceList.isNotEmpty()) {
                    savedDeviceList = savedDeviceList.map { device ->
                        val online = statusMap[device.DeviceID]
                        if (online != null) device.copy(IsOnline = online) else device
                    }
                }
            }
        }
    }

    // Status Polling

    private fun startStatusPolling() {
        stopStatusPolling()
        sendStatusQuery()
        statusPollJob = viewModelScope.launch {
            while (true) {
                delay(3000L)
                sendStatusQuery()
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = null
    }

    private fun sendStatusQuery() {
        localRepository.sendRawCommand("STATUS")
    }

    private fun parseAndApplyStatus(response: String) {
        try {
            val data = response.removePrefix("STATUS:")
            val pairs = data.split(",")
            val list = _switches.value?.toMutableList() ?: return
            var changed = false

            for (pair in pairs) {
                val parts = pair.trim().split(":")
                if (parts.size == 2) {
                    val channel = parts[0].trim().toInt()
                    val isOn = parts[1].trim() == "1"
                    val index = channel - 1
                    if (index in list.indices && list[index].isOn != isOn) {
                        list[index] = list[index].copy(isOn = isOn)
                        changed = true
                    }
                }
            }

            if (changed) {
                _switches.value = list
                saveSwitches()
            }
        } catch (_: Exception) {}
    }

    // Rule Sync

    fun syncAllPendingRules() {
        val list = _rules.value ?: return
        for (i in list.indices) {
            val rule = list[i]
            if (rule.syncStatus == 0 || rule.syncStatus == 2 || rule.syncStatus == 4) {
                if (rule.isCloud) attemptCloudRuleSync(i) else attemptRuleSync(i)
            }
        }
    }

    fun attemptRuleSync(index: Int) {
        val list = _rules.value ?: return
        if (index !in list.indices) return
        val rule = list[index]

        if (rule.syncStatus == 3) return

        if (!localRepository.isConnected) {
            rule.syncStatus = 0
            _rules.value = list.toMutableList()
            saveRules()
            return
        }

        cancelPendingCallbacks(rule.id)

        rule.syncStatus = 4
        rule.lastRetryTime = System.currentTimeMillis()
        _rules.value = list.toMutableList()
        saveRules()

        localRepository.sendRule(rule)
        addLog("规则上传: ${rule.name} (第${rule.retryCount + 1}次)")

        val timeoutRunnable = Runnable { onRuleSyncTimeout(rule.id) }
        pendingTimeouts[rule.id] = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 30_000)
    }

    private fun onRuleSyncTimeout(ruleId: Long) {
        pendingTimeouts.remove(ruleId)
        val list = _rules.value ?: return
        val index = list.indexOfFirst { it.id == ruleId }
        if (index == -1) return

        val rule = list[index]
        rule.retryCount++
        addLog("规则超时: ${rule.name} (第${rule.retryCount}次失败)")

        if (rule.retryCount >= 10) {
            rule.syncStatus = 3
            addLog("规则放弃: ${rule.name}，已达最大重试次数")
        } else {
            rule.syncStatus = 2
            val retryRunnable = Runnable { retryRuleSync(ruleId) }
            retryRunnables[rule.id] = retryRunnable
            mainHandler.postDelayed(retryRunnable, 60_000)
        }

        _rules.value = list.toMutableList()
        saveRules()
    }

    private fun retryRuleSync(ruleId: Long) {
        retryRunnables.remove(ruleId)
        val list = _rules.value ?: return
        val index = list.indexOfFirst { it.id == ruleId }
        if (index == -1) return
        attemptRuleSync(index)
    }

    private fun handleRuleAck(json: String) {
        try {
            val map = gson.fromJson(json, Map::class.java)
            val ruleId = (map["rule_id"] as? Number)?.toLong() ?: return
            val status = map["status"] as? String ?: return
            val success = status == "ok"

            cancelPendingCallbacks(ruleId)

            val list = _rules.value ?: return
            val index = list.indexOfFirst { it.id == ruleId }
            if (index == -1) return

            val rule = list[index]
            if (success) {
                rule.syncStatus = 1
                rule.retryCount = 0
                addLog("规则同步成功: ${rule.name}")
            } else {
                rule.retryCount++
                addLog("规则同步失败: ${rule.name} (第${rule.retryCount}次)")
                if (rule.retryCount >= 10) {
                    rule.syncStatus = 3
                } else {
                    rule.syncStatus = 2
                    val retryRunnable = Runnable { retryRuleSync(ruleId) }
                    retryRunnables[rule.id] = retryRunnable
                    mainHandler.postDelayed(retryRunnable, 60_000)
                }
            }

            _rules.value = list.toMutableList()
            saveRules()
        } catch (_: Exception) {}
    }

    // Cloud Rule Sync

    private fun attemptCloudRuleSync(index: Int) {
        val list = (_rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        val rule = list[index]

        if (!cloudRepository.isConnected()) {
            rule.syncStatus = 0
            _rules.value = list.toMutableList()
            saveRules()
            return
        }

        cancelPendingCallbacks(rule.id)

        rule.syncStatus = 4
        rule.lastRetryTime = System.currentTimeMillis()
        _rules.value = list.toMutableList()
        saveRules()

        if (rule.isTimedTask) {
            val actions = listOf(mapOf(
                "ApiTag" to rule.cloudActuatorApiTag,
                "SetValue" to rule.cloudActionValue,
                "Delay" to 0
            ))

            val runTimes = rule.runTimeSlots.map { slot ->
                mapOf(
                    "Period" to rule.runTimePeriod + 1,
                    "Day" to rule.runTimeDay,
                    "Time" to String.format("T%02d:%02d:00", slot.hour, slot.minute)
                )
            }

            val gatewayDeviceId = cloudDeviceIds.firstOrNull() ?: rule.cloudActuatorDeviceId
            addLog("GatewayID: $gatewayDeviceId")
            addLog("正在创建定时任务: ${rule.name} (${rule.runTimeSlots.size}个时间点)...")

            cloudRepository.addStrategyAsync(
                deviceId = gatewayDeviceId,
                kind = 1,
                expression = "",
                variables = emptyList(),
                actions = actions,
                runTimes = runTimes
            ) { strategyId ->
                mainHandler.post {
                    val rules = (_rules.value ?: return@post).toMutableList()
                    val idx = rules.indexOfFirst { it.id == rule.id }
                    if (idx == -1) return@post
                    val r = rules[idx]

                    if (strategyId != null) {
                        r.cloudStrategyId = strategyId
                        r.syncStatus = 1
                        r.retryCount = 0
                        r.enabled = false
                        addLog("定时任务创建成功: ${r.name} (ID=$strategyId)")
                        mainHandler.postDelayed({ loadCloudStrategies() }, 2000)
                    } else {
                        r.retryCount++
                        addLog("定时任务创建失败: ${r.name} (第${r.retryCount}次)")
                        if (r.retryCount >= 10) {
                            r.syncStatus = 3
                        } else {
                            r.syncStatus = 2
                            val retryRunnable = Runnable { retryCloudRuleSync(r.id) }
                            retryRunnables[r.id] = retryRunnable
                            mainHandler.postDelayed(retryRunnable, 60_000)
                        }
                    }

                    _rules.value = rules.toMutableList()
                    saveRules()
                }
            }

        } else {
            val expression = "{${rule.cloudSensorName}}${rule.operator}${rule.threshold}"
            addLog("条件表达式: $expression")

            val actions = listOf(mapOf(
                "ApiTag" to rule.cloudActuatorApiTag,
                "SetValue" to rule.cloudActionValue,
                "Delay" to 0
            ))

            val runTimes = listOf(mapOf(
                "Period" to 1,
                "Day" to 0
            ))

            val gatewayDeviceId = cloudDeviceIds.firstOrNull() ?: rule.cloudSensorDeviceId
            addLog("GatewayID: $gatewayDeviceId")
            addLog("正在创建条件任务: ${rule.name}...")

            cloudRepository.addStrategyAsync(
                deviceId = gatewayDeviceId,
                kind = 1,
                expression = expression,
                variables = emptyList(),
                actions = actions,
                runTimes = runTimes
            ) { strategyId ->
                mainHandler.post {
                    val rules = (_rules.value ?: return@post).toMutableList()
                    val idx = rules.indexOfFirst { it.id == rule.id }
                    if (idx == -1) return@post
                    val r = rules[idx]

                    if (strategyId != null) {
                        r.cloudStrategyId = strategyId
                        r.syncStatus = 1
                        r.retryCount = 0
                        r.enabled = false
                        addLog("条件任务创建成功: ${r.name} (ID=$strategyId)")
                        mainHandler.postDelayed({ loadCloudStrategies() }, 2000)
                    } else {
                        r.retryCount++
                        addLog("条件任务创建失败: ${r.name} (第${r.retryCount}次)")
                        if (r.retryCount >= 10) {
                            r.syncStatus = 3
                        } else {
                            r.syncStatus = 2
                            val retryRunnable = Runnable { retryCloudRuleSync(r.id) }
                            retryRunnables[r.id] = retryRunnable
                            mainHandler.postDelayed(retryRunnable, 60_000)
                        }
                    }

                    _rules.value = rules.toMutableList()
                    saveRules()
                }
            }
        }
    }

    private fun retryCloudRuleSync(ruleId: Long) {
        retryRunnables.remove(ruleId)
        val list = _rules.value ?: return
        val index = list.indexOfFirst { it.id == ruleId }
        if (index == -1) return
        attemptCloudRuleSync(index)
    }

    fun loadCloudStrategies() {
        if (!cloudRepository.isConnected() || savedProjectId <= 0) return

        cloudRepository.getStrategiesAsync(savedProjectId) { strategies ->
            mainHandler.post {
                val current = (_rules.value ?: mutableListOf<Rule>()).toMutableList()
                current.removeAll { it.isCloud && it.syncStatus == 1 }

                var added = 0
                for (s in strategies) {
                    val strategyId = (s["StrategyId"] as? Number)?.toInt() ?: continue
                    val name = s["GatewayName"] as? String ?: "云策略"
                    val nullity = (s["Nullity"] as? Number)?.toInt() ?: 0
                    val variables = s["VariableList"] as? List<*> ?: emptyList<Any>()
                    val actions = s["ActionList"] as? List<*> ?: emptyList<Any>()
                    val runTimeList = s["RunTimeList"] as? List<*> ?: emptyList<Any>()
                    val condition = s["Condition"] as? String ?: ""

                    if (actions.isEmpty() && condition.isBlank() && runTimeList.isEmpty()) continue
                    if (current.any { it.cloudStrategyId == strategyId }) continue

                    // 从 ActionList 提取执行器信息（可能为空）
                    val firstAction = actions.firstOrNull() as? Map<*, *>
                    val actuatorDeviceId = (firstAction?.get("GatewayDeviceID") as? Number)?.toInt() ?: -1
                    val actuatorApiTag = firstAction?.get("ApiTag") as? String ?: ""
                    val setValue = firstAction?.get("SetValue") as? String ?: "1"
                    val actuatorName = firstAction?.get("GatewayDeviceName") as? String
                        ?: sensorMetaCache["$actuatorDeviceId:$actuatorApiTag"]?.Name
                        ?: sensorMetaCache.entries.firstOrNull { it.value.ApiTag == actuatorApiTag }?.value?.Name
                        ?: actuatorApiTag.ifBlank { name }

                    val isTimedTask = condition.isBlank() && runTimeList.isNotEmpty()

                    if (isTimedTask) {
                        var parsedPeriod = 0
                        var parsedDay = 0
                        val parsedSlots = mutableListOf<RunTimeSlot>()

                        for (rt in runTimeList) {
                            val rtMap = rt as? Map<*, *> ?: continue
                            val timeStr = rtMap["Time"] as? String ?: ""
                            val timePart = timeStr.substringAfter("T", "")
                            val hour = timePart.substringBefore(":").toIntOrNull() ?: 0
                            val minute = timePart.substringAfter(":").substringBefore(":").toIntOrNull() ?: 0
                            parsedPeriod = ((rtMap["Period"] as? Number)?.toInt() ?: 1) - 1
                            parsedDay = (rtMap["Day"] as? Number)?.toInt() ?: 0
                            parsedSlots.add(RunTimeSlot(hour, minute))
                        }

                        if (parsedSlots.isEmpty()) parsedSlots.add(RunTimeSlot(8, 0))

                        val rule = Rule(
                            name = name,
                            operator = ">",
                            threshold = 0f,
                            enabled = nullity == 0,
                            syncStatus = 1,
                            isCloud = true,
                            isTimedTask = true,
                            cloudStrategyId = strategyId,
                            cloudProjectId = savedProjectId,
                            cloudActuatorDeviceId = actuatorDeviceId,
                            cloudActuatorApiTag = actuatorApiTag,
                            cloudActuatorName = actuatorName,
                            cloudActionValue = setValue,
                            runTimePeriod = parsedPeriod,
                            runTimeDay = parsedDay,
                            runTimeSlots = parsedSlots
                        )
                        current.add(rule)
                        added++

                    } else if (condition.isNotBlank()) {
                        // 从 Condition 解析条件，格式: {温度}>30
                        val operatorStr = when {
                            ">=" in condition -> ">="
                            "<=" in condition -> "<="
                            "==" in condition -> "=="
                            ">" in condition -> ">"
                            "<" in condition -> "<"
                            else -> ">"
                        }
                        val sensorNameInCondition = condition.substringBefore(operatorStr).substringAfter("{").substringBefore("}")
                        val thresholdStr = condition.substringAfter(operatorStr).trim()
                        val threshold = thresholdStr.toFloatOrNull() ?: 0f
                        val operatorInt = operatorToInt(operatorStr)

                        // 从 VariableList 获取传感器信息
                        var sensorApiTag = ""
                        var sensorDeviceId = -1
                        var sensorName = sensorNameInCondition

                        if (variables.isNotEmpty()) {
                            val variable = variables[0] as? Map<*, *>
                            if (variable != null) {
                                sensorDeviceId = (variable["GatewayDeviceID"] as? Number)?.toInt() ?: -1
                                sensorApiTag = variable["ApiTag"] as? String ?: ""
                                sensorName = variable["GatewayDeviceName"] as? String
                                    ?: sensorMetaCache["$sensorDeviceId:$sensorApiTag"]?.Name
                                    ?: sensorMetaCache.entries.firstOrNull { it.value.ApiTag == sensorApiTag }?.value?.Name
                                    ?: sensorNameInCondition
                            }
                        } else {
                            for ((key, meta) in sensorMetaCache) {
                                if (meta.Name == sensorNameInCondition) {
                                    sensorApiTag = meta.ApiTag
                                    sensorDeviceId = meta.DeviceID
                                    sensorName = meta.Name
                                    break
                                }
                            }
                        }

                        val rule = Rule(
                            name = name,
                            operator = operatorStr,
                            threshold = threshold,
                            enabled = nullity == 0,
                            syncStatus = 1,
                            isCloud = true,
                            isTimedTask = false,
                            cloudStrategyId = strategyId,
                            cloudProjectId = savedProjectId,
                            cloudSensorDeviceId = sensorDeviceId,
                            cloudSensorApiTag = sensorApiTag,
                            cloudSensorName = sensorName,
                            cloudConditionOperator = operatorInt,
                            cloudActuatorDeviceId = actuatorDeviceId,
                            cloudActuatorApiTag = actuatorApiTag,
                            cloudActuatorName = actuatorName,
                            cloudActionValue = setValue
                        )
                        current.add(rule)
                        added++
                    } else {
                        // 无条件无定时的纯动作策略，作为默认任务展示
                        val rule = Rule(
                            name = name,
                            operator = ">",
                            threshold = 0f,
                            enabled = nullity == 0,
                            syncStatus = 1,
                            isCloud = true,
                            isTimedTask = false,
                            cloudStrategyId = strategyId,
                            cloudProjectId = savedProjectId,
                            cloudActuatorDeviceId = actuatorDeviceId,
                            cloudActuatorApiTag = actuatorApiTag,
                            cloudActuatorName = actuatorName,
                            cloudActionValue = setValue
                        )
                        current.add(rule)
                        added++
                    }
                }

                _rules.value = current.toMutableList()
                saveRules()
                addLog("已从云平台加载 $added 条策略（共 ${strategies.size} 条）")
            }
        }
    }

    private fun cancelPendingCallbacks(ruleId: Long) {
        pendingTimeouts.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
        retryRunnables.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
    }

    // Rule Check

    private val ruleAlertTime = mutableMapOf<String, Long>()
    private fun canAlert(key: String, now: Long): Boolean {
        val last = ruleAlertTime[key] ?: 0L
        return (now - last) > 60_000L
    }
    private fun markAlerted(key: String, now: Long) {
        ruleAlertTime[key] = now
    }

    private fun checkRules(data: SensorData) {
        val sensorMap = mapOf(
            "temp" to data.temp.toDouble(),
            "humi" to data.humi.toDouble(),
            "light" to data.light.toDouble(),
            "pir" to data.pir.toDouble(),
            "flame" to data.flame.toDouble(),
            "gas" to data.gas.toDouble()
        )
        val rulesList = _rules.value ?: return
        val now = System.currentTimeMillis()

        for (rule in rulesList) {
            if (!rule.enabled) continue
            if (rule.isTimedTask) continue

            val sensorVal: Double = resolveSensorValue(rule, sensorMap) ?: continue

            val threshold = rule.threshold.toDouble()
            val triggered = when (rule.operator) {
                ">"  -> sensorVal > threshold
                "<"  -> sensorVal < threshold
                "==" -> sensorVal == threshold
                ">=" -> sensorVal >= threshold
                "<=" -> sensorVal <= threshold
                else -> false
            }

            if (triggered && canAlert("rule_${rule.id}", now)) {
                Log.d("RuleCheck", "规则[${rule.name}]触发! val=$sensorVal op=${rule.operator} threshold=$threshold")
                markAlerted("rule_${rule.id}", now)

                if (rule.isCloud && cloudRepository.isConnected()) {
                    cloudRepository.sendCommand(
                        rule.cloudActuatorDeviceId,
                        rule.cloudActuatorApiTag,
                        rule.cloudActionValue
                    ) { success ->
                        mainHandler.post {
                            addLog(if (success) "云规则执行成功: ${rule.name}"
                            else "云规则执行失败: ${rule.name}")
                        }
                    }
                } else if (!rule.isCloud) {
                    localRepository.sendCommand(rule.actionDevice, rule.actionCmd)
                    when (rule.actionDevice) {
                        "light" -> _lightState.value = (rule.actionCmd == "on")
                        "fan"   -> _fanState.value = (rule.actionCmd == "on")
                    }
                }

                val desc: String
                if (rule.isCloud) {
                    val sName = rule.cloudSensorName.ifBlank { rule.cloudSensorApiTag }
                    val aName = rule.cloudActuatorName.ifBlank { rule.cloudActuatorApiTag }
                    val cmdName = if (rule.cloudActionValue == "1") "开启" else "关闭"
                    desc = "当$sName${rule.operator}${rule.threshold}时，$cmdName$aName"
                } else {
                    val devName = if (rule.actionDevice == "light") "灯光" else "风扇"
                    val cmdName = if (rule.actionCmd == "on") "开启" else "关闭"
                    desc = "当${rule.sensorType}${rule.operator}${rule.threshold}时，$cmdName$devName"
                }
                notificationRepository.sendRuleNotification(rule.name, desc)
                addLog("任务触发: ${rule.name}")
            }
        }
    }

    private fun resolveSensorValue(rule: Rule, sensorMap: Map<String, Double>): Double? {
        if (rule.isCloud) {
            val values = _cloudSensorValues.value ?: emptyMap()
            val apiTag = rule.cloudSensorApiTag

            // 精确匹配 DeviceID:ApiTag
            val exactKey = "${rule.cloudSensorDeviceId}:$apiTag"
            var raw = values[exactKey]

            // 精确匹配不到时，按 ApiTag 模糊查找（策略的 GatewayDeviceID 与传感器实际 DeviceID 可能不同）
            if (raw == null && apiTag.isNotBlank()) {
                raw = values.entries.firstOrNull { it.key.endsWith(":$apiTag") }?.value
                if (raw != null) {
                    Log.d("RuleCheck", "规则[${rule.name}]模糊匹配到 ApiTag=$apiTag")
                }
            }

            if (raw == null) {
                Log.d("RuleCheck", "规则[${rule.name}]云端传感器值缺失 key=$exactKey")
                return null
            }
            val parsed = raw.toDoubleOrNull()
            if (parsed == null) {
                Log.d("RuleCheck", "规则[${rule.name}]值无法解析 raw=$raw")
                return null
            }
            return parsed
        } else {
            val v = sensorMap[rule.sensorType]
            if (v == null) {
                Log.d("RuleCheck", "规则[${rule.name}]本地传感器缺失 type=${rule.sensorType}")
                return null
            }
            return v
        }
    }

    // Public Methods

    fun connectWifi(host: String, port: Int) {
        dataStoreRepository.saveConnectionMode("wifi", host, port)
        localRepository.connectWifi(host, port)
    }

    fun connectBluetooth(device: BluetoothDevice) { localRepository.connectBluetooth(device) }

    fun connectCloud(nleCloudManager: NleCloudManager, projectId: Int = -1) {
        _cloudLoginState.value = true
        _cloudConnected.value = true
        if (projectId > 0) {
            savedProjectId = projectId
            dataStoreRepository.saveProjectSettings(savedProjectId, savedProjectName)
        }
        _connectionStatus.value = "云平台已连接"
        addLog("云平台 HTTP 连接成功")
    }

    // Cloud Device Refresh

    fun refreshCloudDevices() {
        if (!cloudRepository.isConnected() || savedProjectId <= 0) {
            removeCloudCards()
            addLog("云平台未连接，已移除云端卡片")
            return
        }

        addLog("正在刷新云平台设备...")
        stopCloudService()
        doRefreshCloudDevices(savedProjectId)
    }

    fun setupCloudDevices(devices: List<com.smarthome.app.cloud.DeviceBaseInfo>, projectId: Int) {
        cloudDeviceIds = devices.map { it.DeviceID }
        savedDeviceList = devices
        savedProjectId = projectId
        _cloudConnected.value = true
        dataStoreRepository.saveProjectSettings(savedProjectId, savedProjectName)

        addLog("正在获取传感器元数据...")
        doRefreshCloudDevices(projectId)
    }

    private fun doRefreshCloudDevices(projectId: Int) {
        viewModelScope.launch {
            try {
                val freshDevices = cloudDevMgr.getDevices(projectId)
                if (freshDevices.isNotEmpty()) {
                    cloudDeviceIds = freshDevices.map { it.DeviceID }
                    savedDeviceList = freshDevices
                }

                val allSensors = cloudDevMgr.getProjectSensors(projectId)
                val sensorsOnly = allSensors.filter { !it.isActuator }
                val actuatorsOnly = allSensors.filter { it.isActuator }

                if (cloudDeviceIds.isEmpty()) {
                    addLog("未找到设备，跳过刷新")
                    return@launch
                }

                val cache = mutableMapOf<String, SensorPoint>()
                for (s in allSensors) cache["${s.DeviceID}:${s.ApiTag}"] = s

                val deviceIdsStr = cloudDeviceIds.joinToString(",")
                val deferredDeviceSensors = async {
                    cloudDevMgr.fetchDeviceSensorsWithUnit(cloudDeviceIds)
                }
                val deferredDataPoints = async {
                    cloudDevMgr.getSensors(deviceIdsStr)
                }

                val deviceSensors = deferredDeviceSensors.await()
                for ((key, sensor) in deviceSensors) {
                    if (cache[key] == null || cache[key]?.Unit?.isBlank() == true) {
                        cache[key] = sensor
                    }
                }

                val dataPoints = deferredDataPoints.await()

                sensorMetaCache = cache
                sensorMetaCacheStatic = cache
                addLog("刷新完成：传感器 ${sensorsOnly.size} 个，执行器 ${actuatorsOnly.size} 个")

                val sensorDataPoints = dataPoints.filter { dp ->
                    val meta = cache["${dp.DeviceID}:${dp.ApiTag}"]
                    meta != null && !meta.isActuator
                }
                createCloudSensorCards(sensorDataPoints)
                createCloudActuatorCards(actuatorsOnly, dataPoints)

                loadCloudStrategies()
                startCloudService()
            } catch (e: Exception) {
                Log.e("MainVM", "doRefreshCloudDevices failed", e)
                addLog("刷新失败: ${e.message}")
            }
        }
    }

    private fun removeCloudCards() {
        val devices = _sensorDevices.value
        if (devices != null) {
            val newDevices = devices.toMutableList()
            val removed = newDevices.removeAll { it.isCloud }
            if (removed) { _sensorDevices.value = newDevices; saveSensorDevices(); pushSensorDeviceNames() }
        }
        val switches = _switches.value
        if (switches != null) {
            val newSwitches = switches.toMutableList()
            val removed = newSwitches.removeAll { it.isCloud }
            if (removed) { _switches.value = newSwitches; saveSwitches() }
        }
        val rules = _rules.value
        if (rules != null) {
            val newRules = rules.toMutableList()
            val removed = newRules.removeAll { it.isCloud }
            if (removed) { _rules.value = newRules; saveRules() }
        }
    }

    private fun createCloudActuatorCards(
        actuators: List<SensorPoint>,
        dataPoints: List<SensorPoint>
    ) {
        val currentSwitches = (_switches.value ?: mutableListOf<ControlSwitch>()).toMutableList()
        currentSwitches.removeAll { it.isCloud }

        val valueMap = mutableMapOf<String, String>()
        val apiTagToDeviceId = mutableMapOf<String, Int>()
        for (dp in dataPoints) {
            valueMap["${dp.DeviceID}:${dp.ApiTag}"] = dp.Value
            apiTagToDeviceId[dp.ApiTag] = dp.DeviceID
        }

        for (act in actuators) {
            val correctDeviceId = apiTagToDeviceId[act.ApiTag] ?: act.DeviceID

            val cacheKey = "${act.DeviceID}:${act.ApiTag}"
            val meta = sensorMetaCache[cacheKey]
            val displayName = meta?.Name?.takeIf { it.isNotBlank() } ?: act.ApiTag
            val icon = guessActuatorIcon(act.ApiTag, displayName, act.OperType)

            val currentValue = valueMap["${correctDeviceId}:${act.ApiTag}"] ?: valueMap[cacheKey] ?: "0"
            val isOn = currentValue == "1" || currentValue.equals("true", ignoreCase = true)

            val sw = ControlSwitch(
                id = correctDeviceId * 10000L + act.ApiTag.hashCode().toLong(),
                name = displayName,
                icon = icon,
                nodeAddr = "",
                onCommand = "",
                offCommand = "",
                isOn = isOn,
                source = "cloud",
                cloudDeviceId = correctDeviceId,
                cloudApiTag = act.ApiTag,
                operType = act.OperType
            )
            currentSwitches.add(sw)
        }

        _switches.value = currentSwitches
        saveSwitches()
    }

    private fun guessActuatorIcon(apiTag: String, name: String, operType: Int): String {
        val tag = apiTag.lowercase()
        val n = name.lowercase()
        return when {
            "fan" in tag || "风扇" in n || "风机" in n -> "\uD83C\uDF00"
            "light" in tag || "灯" in n || "led" in tag -> "\uD83D\uDCA1"
            "door" in tag || "门" in n -> "\uD83D\uDEAA"
            "motor" in tag || "电机" in n -> "\u26A1"
            "pump" in tag || "泵" in n -> "\uD83D\uDCA7"
            "valve" in tag || "阀" in n -> "\uD83D\uDD27"
            "relay" in tag || "继电器" in n -> "\uD83D\uDD0C"
            "alarm" in tag || "警报" in n -> "\uD83D\uDD0A"
            operType == 1 -> "\uD83D\uDCA1"
            operType == 3 -> "\uD83D\uDD33"
            operType == 4 -> "\uD83C\uDF9A"
            else -> "\u26A1"
        }
    }

    private fun createCloudSensorCards(dataPoints: List<SensorPoint>) {
        val current = _sensorDevices.value ?: mutableListOf()
        current.removeAll { it.isCloud }

        for (dp in dataPoints) {
            val cacheKey = "${dp.DeviceID}:${dp.ApiTag}"
            val meta = sensorMetaCache[cacheKey]

            if (meta == null) continue

            val displayName = meta.Name.takeIf { it.isNotBlank() } ?: dp.ApiTag
            val unit = meta.Unit.takeIf { it.isNotBlank() }
                ?: guessSensorUnit(dp.ApiTag, displayName)
            val icon = guessSensorIcon(dp.ApiTag, displayName)

            val matchedLocalType = matchLocalType(displayName, dp.ApiTag)

            val item = SensorDeviceItem(
                id = dp.DeviceID * 10000L + dp.ApiTag.hashCode().toLong(),
                icon = icon,
                name = displayName,
                sensorType = if (matchedLocalType != null) matchedLocalType else "cloud_sensor",
                unit = unit,
                source = "cloud",
                cloudDeviceId = dp.DeviceID,
                cloudApiTag = dp.ApiTag,
                cloudDeviceName = dp.DeviceName,
                matchedLocalType = matchedLocalType
            )
            current.add(item)
        }

        _sensorDevices.value = current.toMutableList()
        saveSensorDevices()
        pushSensorDeviceNames()
    }

    private fun guessSensorIcon(apiTag: String, name: String): String {
        val tag = apiTag.lowercase()
        val n = name.lowercase()
        return when {
            "temp" in tag || "温度" in n     -> "\uD83C\uDF21"
            "humi" in tag || "湿度" in n     -> "\uD83D\uDCA7"
            "light" in tag || "光照" in n || "亮度" in n -> "\u2600"
            "pir" in tag || "人体" in n || "红外" in n   -> "\u25CB"
            "flame" in tag || "火" in n      -> "\uD83D\uDD25"
            "gas" in tag || "燃气" in n || "烟雾" in n   -> "\uD83D\uDCA8"
            "wind" in tag || "风速" in n     -> "\uD83C\uDF00"
            "co2" in tag || "二氧化碳" in n  -> "\uD83E\uDEC1"
            "pm" in tag || "粉尘" in n       -> "\uD83C\uDF2B"
            else -> "\uD83D\uDCE1"
        }
    }

    private fun guessSensorUnit(apiTag: String, name: String): String {
        val tag = apiTag.lowercase()
        val n = name.lowercase()
        return when {
            "temp" in tag || "温度" in n     -> "\u00B0C"
            "humi" in tag || "湿度" in n     -> "%RH"
            "light" in tag || "光照" in n || "亮度" in n -> "lux"
            "pir" in tag || "人体" in n || "红外" in n   -> ""
            "flame" in tag || "火" in n      -> ""
            "gas" in tag || "燃气" in n || "烟雾" in n   -> "ppm"
            "wind" in tag || "风速" in n     -> "m/s"
            "co2" in tag || "二氧化碳" in n  -> "ppm"
            "pm" in tag || "粉尘" in n       -> "\u03BCg/m\u00B3"
            "wendu" in tag -> "\u00B0C"
            "shidu" in tag -> "%RH"
            "nl_fan" in tag -> ""
            else -> ""
        }
    }

    private fun startCloudService() {
        if (cloudDeviceIds.isEmpty() || savedProjectId <= 0) return
        addLog("启动云平台轮询服务")
        CloudPollingService.start(getApplication(), savedProjectId, cloudDeviceIds, cloudRepository.getAccessToken())
    }

    private fun stopCloudService() {
        CloudPollingService.stop(getApplication())
    }

    private fun syncStrategyStates(strategies: List<Map<String, Any?>>) {
        val rules = _rules.value ?: return
        var changed = false
        for (s in strategies) {
            val strategyId = (s["StrategyId"] as? Number)?.toInt() ?: continue
            val nullity = (s["Nullity"] as? Number)?.toInt() ?: 0
            val shouldBeEnabled = nullity == 0
            val rule = rules.firstOrNull { it.isCloud && it.cloudStrategyId == strategyId }
            if (rule != null && rule.enabled != shouldBeEnabled) {
                rule.enabled = shouldBeEnabled
                changed = true
                addLog("策略状态更新: ${rule.name} → ${if (shouldBeEnabled) "启用" else "禁用"}")
            }
        }
        if (changed) {
            _rules.value = rules.toMutableList()
            saveRules()
        }
    }

    private fun updateActuatorStatesFromCloud(sensors: List<SensorPoint>) {
        val current = _switches.value ?: return
        val newSwitches = current.toMutableList()
        var changed = false

        val cloudActuatorTags = current.filter { it.isCloud }.map { it.cloudApiTag }.toSet()

        for (sensor in sensors) {
            if (sensor.ApiTag !in cloudActuatorTags) continue

            val idx = newSwitches.indexOfFirst { it.isCloud && it.cloudApiTag == sensor.ApiTag }
            if (idx == -1) continue

            val sw = newSwitches[idx]
            val rawValue = sensor.Value.trim()
            val newValue = when (rawValue) {
                "1", "1.0", "true", "True", "TRUE" -> true
                "0", "0.0", "false", "False", "FALSE" -> false
                else -> continue
            }

            if (sw.isOn != newValue) {
                newSwitches[idx] = sw.copy(isOn = newValue)
                changed = true
                addLog("执行器状态更新: ${sw.name} ($rawValue) → ${if (newValue) "开启" else "关闭"}")
            }
        }

        if (changed) {
            _switches.value = newSwitches
            saveSwitches()
        }
    }

    private fun updateSensorDataFromCloud(sensors: List<SensorPoint>) {
        var temp = 0f; var humi = 0f; var light = 0
        var pir = 0; var flame = 0; var gas = 0

        for (s in sensors) {
            val tag = s.ApiTag.lowercase()
            when {
                "temp" in tag  -> temp = s.Value.toFloatOrNull() ?: 0f
                "humi" in tag  -> humi = s.Value.toFloatOrNull() ?: 0f
                "light" in tag -> light = s.Value.toIntOrNull() ?: 0
                "pir" in tag   -> pir = s.Value.toIntOrNull() ?: 0
                "flame" in tag -> flame = s.Value.toIntOrNull() ?: 0
                "gas" in tag   -> gas = s.Value.toIntOrNull() ?: 0
            }
        }

        val data = SensorData(
            temp = temp, humi = humi, light = light,
            pir = pir, flame = flame, gas = gas
        )
        _sensorData.value = data
        notificationRepository.checkAlerts(data)
        checkRules(data)
    }

    // Disconnect

    fun logoutCloud() {
        stopCloudService()
        cloudDeviceIds = emptyList()
        _cloudSensorValues.value = emptyMap()
        removeCloudCards()

        cloudRepository.disconnect()
        _cloudLoginState.value = false
        _cloudConnected.value = false
        savedProjectList = emptyList()
        savedProjectId = -1
        savedProjectName = ""
        savedDeviceList = emptyList()

        dataStoreRepository.saveProjectSettings(-1, "")
        cloudRepository.clearNlePrefs()

        _connectionStatus.value = "未连接"
        addLog("已退出云平台登录")
    }

    fun disconnectCloudOnly() {
        stopCloudService()
        cloudDeviceIds = emptyList()
        _cloudSensorValues.value = emptyMap()
        removeCloudCards()

        _cloudConnected.value = false
        savedDeviceList = emptyList()
        savedProjectId = -1
        savedProjectName = ""

        dataStoreRepository.saveProjectSettings(-1, "")

        _connectionStatus.value = "未连接"
        addLog("云平台连接已断开")
    }

    fun disconnect() {
        stopStatusPolling()
        localRepository.disconnect()
    }

    fun toggleLight() {
        val newState = !(_lightState.value ?: false)
        localRepository.sendCommand("light", if (newState) "on" else "off")
        _lightState.value = newState
    }

    fun toggleFan() {
        val newState = !(_fanState.value ?: false)
        localRepository.sendCommand("fan", if (newState) "on" else "off")
        _fanState.value = newState
    }

    // Rule CRUD

    fun addRule(rule: Rule) {
        val list = (_rules.value ?: mutableListOf()).toMutableList()
        list.add(rule)
        _rules.value = list.toMutableList()
        saveRules()

        if (rule.isCloud) {
            attemptCloudRuleSync(list.size - 1)
        } else {
            attemptRuleSync(list.size - 1)
        }
    }

    fun removeRule(index: Int) {
        val list = (_rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        val rule = list[index]

        cancelPendingCallbacks(rule.id)

        if (rule.isCloud && rule.cloudStrategyId > 0) {
            cloudRepository.deleteStrategyAsync(listOf(rule.cloudStrategyId)) { success ->
                mainHandler.post {
                    addLog(if (success) "云策略已删除: ${rule.name}"
                    else "云策略删除失败: ${rule.name}")
                }
            }
        }

        list.removeAt(index)
        _rules.value = list.toMutableList()
        saveRules()
    }

    fun toggleRule(index: Int) {
        val list = (_rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        val rule = list[index]

        rule.enabled = !rule.enabled
        _rules.value = list.toMutableList()
        saveRules()

        if (rule.isCloud && rule.cloudStrategyId > 0) {
            cloudRepository.enableStrategyAsync(rule.cloudStrategyId, rule.enabled) { success ->
                mainHandler.post {
                    addLog(if (success) "云策略${if (rule.enabled) "启用" else "禁用"}: ${rule.name}"
                    else "云策略状态切换失败: ${rule.name}")
                }
            }
        } else if (!rule.isCloud) {
            rule.syncStatus = 0
            attemptRuleSync(index)
        }
    }

    fun manualRetryRule(index: Int) {
        val list = (_rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        list[index].retryCount = 0
        list[index].syncStatus = 0
        _rules.value = list.toMutableList()
        saveRules()

        if (list[index].isCloud) {
            attemptCloudRuleSync(index)
        } else {
            attemptRuleSync(index)
        }
    }

    // Cloud Helpers

    fun getCloudSensors(): List<SensorPoint> {
        return sensorMetaCache.values.filter { !it.isActuator }
    }

    fun getCloudActuators(): List<SensorPoint> {
        return sensorMetaCache.values.filter { it.isActuator }
    }

    fun getDeviceName(deviceId: Int): String {
        return savedDeviceList.firstOrNull { it.DeviceID == deviceId }?.Name ?: "设备$deviceId"
    }

    fun getCloudSensorDeviceItems(): List<SensorDeviceItem> {
        return _sensorDevices.value?.filter { it.source == "cloud" } ?: emptyList()
    }

    fun getCloudActuatorSwitches(): List<ControlSwitch> {
        return _switches.value?.filter { it.isCloud } ?: emptyList()
    }

    private fun intToOperator(i: Int): String = when (i) {
        1 -> ">"; 2 -> "<"; 3 -> "=="; 4 -> ">="; 5 -> "<="; else -> ">"
    }

    private fun operatorToInt(op: String): Int = when (op) {
        ">" -> 1; "<" -> 2; "==" -> 3; ">=" -> 4; "<=" -> 5; else -> 1
    }

    // Sensor Card Management

    fun addSensorDevice(item: SensorDeviceItem) {
        val list = _sensorDevices.value ?: mutableListOf()
        val newList = list.toMutableList()
        newList.add(item)
        _sensorDevices.value = newList
        saveSensorDevices()
        pushSensorDeviceNames()
    }

    fun removeSensorDevice(id: Long) {
        val list = _sensorDevices.value ?: return
        val newList = list.toMutableList()
        val removed = newList.removeAll { it.id == id }
        if (removed) {
            _sensorDevices.value = newList
            saveSensorDevices()
            pushSensorDeviceNames()
        }
    }

    fun deleteCloudSensor(deviceId: Int, apiTag: String, callback: (Boolean) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false); return }
        viewModelScope.launch {
            val success = cloudRepository.deleteSensor(deviceId, apiTag)
            mainHandler.post {
                if (success) addLog("云传感器已删除: $apiTag")
                else addLog("云传感器删除失败: $apiTag")
                callback(success)
            }
        }
    }

    fun createCloudSensor(
        deviceId: Int,
        apiTag: String,
        name: String,
        unit: String,
        transType: Int = 0,
        operType: Int = 0,
        sensorType: String = "float",
        callback: (Boolean, String) -> Unit
    ) {
        if (!cloudRepository.isConnected()) {
            callback(false, "云平台未连接")
            return
        }

        viewModelScope.launch {
            try {
                val sensor = cloudDevMgr.createSensor(deviceId, apiTag, name, unit, transType, operType, sensorType)
                if (sensor != null) {
                    val matchedLocalType = matchLocalType(name, apiTag)
                    val icon = guessSensorIcon(apiTag, name)
                    val item = SensorDeviceItem(
                        id = deviceId * 10000L + apiTag.hashCode().toLong(),
                        icon = icon,
                        name = name,
                        sensorType = if (matchedLocalType != null) matchedLocalType else "cloud_sensor",
                        unit = unit,
                        source = "cloud",
                        cloudDeviceId = deviceId,
                        cloudApiTag = apiTag,
                        cloudDeviceName = "",
                        matchedLocalType = matchedLocalType
                    )
                    addSensorDevice(item)
                    addLog("云传感器创建成功: $name ($apiTag)")
                    callback(true, "传感器创建成功")
                } else {
                    addLog("云传感器创建失败: $name")
                    callback(false, "传感器创建失败")
                }
            } catch (e: Exception) {
                Log.e("MainVM", "createCloudSensor failed", e)
                addLog("云传感器创建异常: ${e.message}")
                callback(false, e.message ?: "创建异常")
            }
        }
    }

    fun createCloudActuator(
        deviceId: Int,
        apiTag: String,
        name: String,
        operType: Int = 1,
        callback: (Boolean, String) -> Unit
    ) {
        if (!cloudRepository.isConnected()) {
            callback(false, "云平台未连接")
            return
        }

        viewModelScope.launch {
            try {
                val sensor = cloudDevMgr.createSensor(
                    deviceId = deviceId,
                    apiTag = apiTag,
                    name = name,
                    unit = "",
                    transType = 1,
                    operType = operType,
                    sensorType = "bool"
                )
                if (sensor != null) {
                    val sw = ControlSwitch(
                        name = name,
                        icon = guessActuatorIcon(apiTag, name, operType),
                        nodeAddr = "",
                        onCommand = "",
                        offCommand = "",
                        isOn = false,
                        source = "cloud",
                        cloudDeviceId = deviceId,
                        cloudApiTag = apiTag,
                        operType = operType
                    )
                    addSwitch(sw)
                    addLog("云执行器创建成功: $name ($apiTag)")
                    callback(true, "执行器创建成功")
                } else {
                    addLog("云执行器创建失败: $name")
                    callback(false, "执行器创建失败")
                }
            } catch (e: Exception) {
                Log.e("MainVM", "createCloudActuator failed", e)
                addLog("云执行器创建异常: ${e.message}")
                callback(false, e.message ?: "创建异常")
            }
        }
    }

    private fun getLocalTypeKeywords(): Map<String, List<String>> {
        return mapOf(
            "temp"  to listOf("温度", "气温", "体温"),
            "humi"  to listOf("湿度", "含湿"),
            "light" to listOf("光照", "亮度", "光强"),
            "pir"   to listOf("人体", "红外", "有人"),
            "gas"   to listOf("燃气", "烟雾", "气体", "可燃"),
            "flame" to listOf("火焰", "火灾", "明火")
        )
    }

    private fun matchLocalType(sensorName: String, apiTag: String): String? {
        val name = sensorName.lowercase()
        val tag = apiTag.lowercase()

        for ((type, keywords) in getLocalTypeKeywords()) {
            if (type in tag) return type
            for (keyword in keywords) {
                if (keyword in name) return type
            }
        }

        val allTypes = SensorDeviceItem.getAllTypes()
        for (localType in allTypes) {
            val localName = localType.name
            if (name.length >= 2 && localName.length >= 2) {
                for (i in 0..localName.length - 2) {
                    val sub = localName.substring(i, i + 2)
                    if (sub in name) return localType.sensorType
                }
            }
        }

        return null
    }

    // Switch Management

    fun addSwitch(sw: ControlSwitch) {
        val list = (_switches.value ?: mutableListOf()).toMutableList()
        list.add(sw)
        _switches.value = list.toMutableList()
        saveSwitches()
    }

    fun updateSwitch(index: Int, sw: ControlSwitch) {
        val list = (_switches.value ?: return).toMutableList()
        if (index in list.indices) {
            list[index] = sw
            _switches.value = list.toMutableList()
            saveSwitches()
        }
    }

    fun removeSwitch(index: Int) {
        val list = (_switches.value ?: return).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _switches.value = list.toMutableList()
            saveSwitches()
        }
    }

    fun toggleSwitch(index: Int) {
        val list = _switches.value ?: return
        if (index !in list.indices) return
        val sw = list[index]
        val newIsOn = !sw.isOn

        if (sw.isCloud) {
            val device = savedDeviceList.firstOrNull { it.DeviceID == sw.cloudDeviceId }
            if (device != null && !device.IsOnline) {
                addLog("设备离线，操作失败: ${sw.name}")
                return
            }
            val value = if (newIsOn) "1" else "0"
            cloudRepository.sendCommand(sw.cloudDeviceId, sw.cloudApiTag, value) { success ->
                mainHandler.post {
                    if (success) {
                        val newList = list.toMutableList()
                        newList[index] = sw.copy(isOn = newIsOn)
                        _switches.value = newList
                        saveSwitches()
                        addLog("云命令成功: ${sw.name} → ${if (newIsOn) "开启" else "关闭"}")
                    } else {
                        addLog("云命令失败: ${sw.name}")
                    }
                }
            }
        } else {
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            localRepository.sendRawCommand(cmd)
            val newList = list.toMutableList()
            newList[index] = sw.copy(isOn = newIsOn)
            _switches.value = newList
            saveSwitches()
        }
    }

    fun toggleSwitchSilent(index: Int) {
        val list = _switches.value ?: return
        if (index !in list.indices) return
        val sw = list[index]
        val newIsOn = !sw.isOn

        if (sw.isCloud) {
            val device = savedDeviceList.firstOrNull { it.DeviceID == sw.cloudDeviceId }
            if (device != null && !device.IsOnline) {
                addLog("设备离线，操作失败: ${sw.name}")
                return
            }
            val newList = list.toMutableList()
            newList[index] = sw.copy(isOn = newIsOn)
            _switches.value = newList
            saveSwitches()

            val value = if (newIsOn) "1" else "0"
            cloudRepository.sendCommand(sw.cloudDeviceId, sw.cloudApiTag, value) { success ->
                mainHandler.post {
                    if (!success) {
                        val currentList = _switches.value?.toMutableList() ?: return@post
                        val curIdx = currentList.indexOfFirst { it.id == sw.id }
                        if (curIdx >= 0) {
                            currentList[curIdx] = currentList[curIdx].copy(isOn = !newIsOn)
                            _switches.value = currentList
                            saveSwitches()
                        }
                        addLog("云命令失败: ${sw.name}，已回滚")
                    }
                }
            }
        } else {
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            localRepository.sendRawCommand(cmd)
            val newList = list.toMutableList()
            newList[index] = sw.copy(isOn = newIsOn)
            _switches.value = newList
            saveSwitches()
        }
    }

    // Notification

    fun saveNotifSettings(settings: Map<String, Any>) = dataStoreRepository.saveNotifSettings(settings)

    fun getNotifSettings(): Map<String, Any> = dataStoreRepository.getNotifSettings()

    fun testNotification() = notificationRepository.testNotification()

    fun sendRawCmd(json: String) {
        localRepository.sendRawCommand(json)
        addLog("手动发送: $json")
    }

    fun clearLogs() {
        _logMessages.value = mutableListOf()
    }

    // Private Helpers

    private fun handleControlAck(json: String) {
        try {
            val map = gson.fromJson(json, Map::class.java)
            val device = map["device"] as? String ?: return
            val action = map["action"] as? String ?: return
            when (device) {
                "light" -> _lightState.value = (action == "on")
                "fan" -> _fanState.value = (action == "on")
            }
        } catch (_: Exception) {}
    }

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val old = _logMessages.value ?: emptyList()
        val list = mutableListOf("[$time] $msg")
        list.addAll(old)
        if (list.size > 100) list.removeAt(list.lastIndex)
        _logMessages.value = list
    }

    private fun saveRules() {
        dataStoreRepository.saveRules(_rules.value ?: emptyList())
    }

    private fun loadRules() {
        _rules.value = dataStoreRepository.loadRules()
    }

    private fun saveSwitches() {
        dataStoreRepository.saveSwitches(_switches.value ?: emptyList())
    }

    private fun loadSwitches() {
        _switches.value = dataStoreRepository.loadSwitches()
    }

    private fun saveSensorDevices() {
        dataStoreRepository.saveSensorDevices(_sensorDevices.value ?: emptyList())
    }

    private fun loadSensorDevices() {
        _sensorDevices.value = dataStoreRepository.loadSensorDevices()
        pushSensorDeviceNames()
    }

    private fun pushSensorDeviceNames() {
        val devices = _sensorDevices.value ?: return
        val map = mutableMapOf<String, String>()
        for (d in devices) {
            if (d.sensorType.isNotBlank()) {
                map[d.sensorType] = d.name
            }
        }
        notificationRepository.setSensorDeviceNames(map)
    }

    fun saveProjectList(list: List<com.smarthome.app.cloud.ProjectInfo>) {
        savedProjectList = list
    }

    fun getSavedProjectList(): List<com.smarthome.app.cloud.ProjectInfo> = savedProjectList

    fun getSavedProjectId(): Int = savedProjectId

    fun getSavedProjectName(): String = savedProjectName

    fun saveProjectName(name: String) {
        savedProjectName = name
    }

    fun getSavedDeviceList(): List<com.smarthome.app.cloud.DeviceBaseInfo> = savedDeviceList

    private fun loadProjectSettings() {
        val (id, name) = dataStoreRepository.loadProjectSettings()
        savedProjectId = id
        savedProjectName = name
    }

    // Auto Login

    fun autoLogin() {
        val (account, password) = cloudRepository.getSavedCredentials()
        if (account.isBlank() || password.isBlank()) {
            addLog("无保存的云平台凭证，跳过自动登录")
            removeCloudCards()
            return
        }

        addLog("正在自动登录云平台...")
        _cloudLoginState.value = true

        viewModelScope.launch {
            val (ok, msg) = cloudRepository.login(account, password)
            if (ok) {
                addLog("云平台自动登录成功")
                loadProjectSettings()
                if (savedProjectId > 0) {
                    connectToSavedProject()
                } else {
                    loadProjectsAndConnect()
                }
            } else {
                addLog("云平台自动登录失败: $msg")
                _cloudLoginState.value = false
                removeCloudCards()
            }
        }
    }

    private suspend fun connectToSavedProject() {
        addLog("正在连接项目: $savedProjectName (ID=$savedProjectId)")
        val ok = cloudRepository.connectToProject(savedProjectId)
        if (ok) {
            _cloudConnected.value = true
            _connectionStatus.value = "云平台已连接 - $savedProjectName"
            addLog("项目连接成功: $savedProjectName")

            if (cloudDeviceIds.isEmpty()) {
                val devices = cloudDevMgr.getDevices(savedProjectId)
                if (devices.isNotEmpty()) {
                    cloudDeviceIds = devices.map { it.DeviceID }
                    savedDeviceList = devices
                    addLog("获取到 ${devices.size} 个设备")
                } else {
                    addLog("该项目下无设备")
                }
            }

            refreshCloudDevices()
        } else {
            addLog("项目连接失败")
            _cloudConnected.value = false
            _connectionStatus.value = "未连接"
            removeCloudCards()
        }
    }

    private suspend fun loadProjectsAndConnect() {
        val projects = cloudDevMgr.getProjects()
        if (projects.isEmpty()) {
            addLog("未找到云平台项目，请创建")
            savedProjectList = emptyList()
        } else {
            savedProjectList = projects
            val project = projects.first()
            savedProjectId = project.ProjectID
            savedProjectName = project.Name
            dataStoreRepository.saveProjectSettings(savedProjectId, savedProjectName)
            addLog("找到 ${projects.size} 个项目，自动连接: ${project.Name}")
            connectToSavedProject()
        }
    }

    fun createProject(name: String, callback: (Boolean, String) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false, "云平台未连接"); return }
        viewModelScope.launch {
            val id = cloudDevMgr.createProject(name)
            if (id != null) {
                addLog("项目创建成功: $name (ID=$id)")
                val projects = cloudDevMgr.getProjects()
                savedProjectList = projects
                savedProjectId = id
                savedProjectName = name
                dataStoreRepository.saveProjectSettings(savedProjectId, savedProjectName)
                callback(true, "项目创建成功")
            } else {
                addLog("项目创建失败: $name")
                callback(false, "项目创建失败")
            }
        }
    }

    fun deleteProject(projectId: Int, callback: (Boolean) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false); return }
        viewModelScope.launch {
            val ok = cloudDevMgr.deleteProject(listOf(projectId))
            if (ok) {
                addLog("项目已删除: ID=$projectId")
                val projects = cloudDevMgr.getProjects()
                savedProjectList = projects
                if (savedProjectId == projectId) {
                    savedProjectId = -1
                    savedProjectName = ""
                    dataStoreRepository.saveProjectSettings(-1, "")
                }
            } else {
                addLog("项目删除失败: ID=$projectId")
            }
            callback(ok)
        }
    }

    fun createDevice(projectId: Int, name: String, tag: String, protocol: Int = 2, callback: (Boolean, String) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false, "云平台未连接"); return }
        viewModelScope.launch {
            val (id, errorMsg) = cloudDevMgr.createDevice(projectId, name, tag, protocol)
            if (id != null) {
                addLog("设备创建成功: $name (ID=$id)")
                cloudDeviceIds = cloudDeviceIds + id
                stopCloudService()
                startCloudService()
                callback(true, "设备创建成功")
            } else {
                addLog("设备创建失败: $name - $errorMsg")
                callback(false, errorMsg)
            }
        }
    }

    fun deleteDevice(deviceId: Int, callback: (Boolean) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false); return }
        viewModelScope.launch {
            val ok = cloudDevMgr.deleteDevice(deviceId)
            if (ok) {
                addLog("设备已删除: ID=$deviceId")
                cloudDeviceIds = cloudDeviceIds.filter { it != deviceId }
                removeDeviceSensorCards(deviceId)
                stopCloudService()
                if (cloudDeviceIds.isNotEmpty()) {
                    startCloudService()
                }
            } else {
                addLog("设备删除失败: ID=$deviceId")
            }
            callback(ok)
        }
    }

    // 子 ViewModel 委托方法

    fun updateSensorDevices(list: List<SensorDeviceItem>) {
        _sensorDevices.value = list.toMutableList()
        pushSensorDeviceNames()
    }

    fun updateSwitches(list: List<ControlSwitch>) {
        _switches.value = list.toMutableList()
    }

    fun updateRules(list: List<Rule>) {
        _rules.value = list.toMutableList()
    }

    fun updateLightState(state: Boolean) { _lightState.value = state }
    fun updateFanState(state: Boolean) { _fanState.value = state }

    fun cloudDeviceIds(): List<Int> = cloudDeviceIds

    fun updateSavedProject(id: Int, name: String) {
        savedProjectId = id
        savedProjectName = name
    }

    private fun removeDeviceSensorCards(deviceId: Int) {
        val devices = _sensorDevices.value
        if (devices != null) {
            val newDevices = devices.toMutableList()
            val removed = newDevices.removeAll { it.isCloud && it.cloudDeviceId == deviceId }
            if (removed) { _sensorDevices.value = newDevices; saveSensorDevices(); pushSensorDeviceNames() }
        }
        val switches = _switches.value
        if (switches != null) {
            val newSwitches = switches.toMutableList()
            val removed = newSwitches.removeAll { it.isCloud && it.cloudDeviceId == deviceId }
            if (removed) { _switches.value = newSwitches; saveSwitches() }
        }
    }
}

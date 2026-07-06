package com.smarthome.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.GsonBuilder/////
import com.google.gson.reflect.TypeToken
import com.smarthome.app.model.*
import com.smarthome.app.network.ConnectionManager
import com.smarthome.app.notification.NotificationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import com.smarthome.app.cloud.*
import android.util.Log

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("smart_home_prefs", Context.MODE_PRIVATE)
    ///private val gson = Gson()
    val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    private val cm = ConnectionManager.instance
    private val notifHelper = NotificationHelper(application)

    private val lastAlertTime = mutableMapOf<String, Long>()
    private val ALERT_COOLDOWN = 60_000L

    private val pendingTimeouts = mutableMapOf<Long, Runnable>()
    private val retryRunnables = mutableMapOf<Long, Runnable>()

    private val _sensorData = MutableLiveData<SensorData>()
    val sensorData: LiveData<SensorData> = _sensorData

    private val _connectionStatus = MutableLiveData("未连接")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _lightState = MutableLiveData(false)
    val lightState: LiveData<Boolean> = _lightState

    private val _fanState = MutableLiveData(false)
    val fanState: LiveData<Boolean> = _fanState

    private val _logMessages = MutableLiveData<MutableList<String>>(mutableListOf())
    val logMessages: LiveData<MutableList<String>> = _logMessages

    private val _rules = MutableLiveData<MutableList<Rule>>(mutableListOf())
    val rules: LiveData<MutableList<Rule>> = _rules

    private val _switches = MutableLiveData<MutableList<ControlSwitch>>(mutableListOf())
    val switches: LiveData<MutableList<ControlSwitch>> = _switches

    // 云平台相关
    private var cloudPollTimer: Timer? = null
    private var strategyPollTimer: Timer? = null
    private var cloudDeviceIds: List<Int> = emptyList()

    val cloudManager: NleCloudManager by lazy {
        NleCloudManager(getApplication())
    }

    private val _cloudSensorValues = MutableLiveData<Map<String, String>>(emptyMap())
    val cloudSensorValues: LiveData<Map<String, String>> = _cloudSensorValues

    private val _cloudLoginState = MutableLiveData(false)
    val cloudLoginState: LiveData<Boolean> = _cloudLoginState

    private val _cloudConnected = MutableLiveData(false)
    val cloudConnected: LiveData<Boolean> = _cloudConnected

    private var savedProjectList: List<ProjectInfo> = emptyList()
    private var savedProjectId: Int = -1
    private var savedProjectName: String = ""
    private var savedDeviceList: List<DeviceBaseInfo> = emptyList()

    // 传感器缓存
    private var sensorMetaCache: Map<String, SensorPoint> = emptyMap()

    private val _sensorDevices = MutableLiveData<MutableList<SensorDeviceItem>>(mutableListOf())
    val sensorDevices: LiveData<MutableList<SensorDeviceItem>> = _sensorDevices

    private var statusTimer: Timer? = null

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
                    checkSensorAlerts(data)
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
                ConnectionManager.MSG_RULE_ACK -> {
                    val json = msg.obj as String
                    handleRuleAck(json)
                }
                ConnectionManager.MSG_ERROR -> {
                    addLog("错误: ${msg.obj}")
                    _connectionStatus.value = "错误: ${msg.obj}"
                }
                ConnectionManager.MSG_LOG -> {
                    val text = msg.obj as String
                    if (text.startsWith("STATUS:")) {
                        parseAndApplyStatus(text)
                    }
                    addLog(text)
                }
            }
        }
    }

    init {
        cm.setHandler(mainHandler)
        loadRules()
        loadSwitches()
        loadSensorDevices()
    }

    /**
     * 延迟自动登录，在 Activity 准备好后调用
     */
    fun startAutoLogin() {
        autoLogin()
    }

    // 纠错
    fun correctSwitchState(index: Int) {
        val list = _switches.value ?: return
        if (index !in list.indices) return
        list[index].isOn = !list[index].isOn
        _switches.value = list
        saveSwitches()
    }

    // 状态轮询
    private fun startStatusPolling() {
        stopStatusPolling()
        sendStatusQuery()
        statusTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() { sendStatusQuery() }
            }, 3000L, 3000L)
        }
    }

    private fun stopStatusPolling() {
        statusTimer?.cancel()
        statusTimer = null
    }

    private fun sendStatusQuery() {
        cm.sendRawCommand("STATUS")
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
                        list[index].isOn = isOn
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

    // ========== 规则同步 ==========

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

        if (!cm.isConnected) {
            rule.syncStatus = 0
            _rules.value = list
            saveRules()
            return
        }

        cancelPendingCallbacks(rule.id)

        rule.syncStatus = 4
        rule.lastRetryTime = System.currentTimeMillis()
        _rules.value = list
        saveRules()

        cm.sendRule(rule)
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

        _rules.value = list
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

            _rules.value = list
            saveRules()
        } catch (_: Exception) {}
    }

    // ========== 云平台策略同步 ==========

    private fun attemptCloudRuleSync(index: Int) {
        val list = _rules.value ?: return
        if (index !in list.indices) return
        val rule = list[index]

        if (!cloudManager.isConnected()) {
            rule.syncStatus = 0
            _rules.value = list
            saveRules()
            return
        }

        cancelPendingCallbacks(rule.id)

        rule.syncStatus = 4
        rule.lastRetryTime = System.currentTimeMillis()
        _rules.value = list
        saveRules()

        if (rule.isTimedTask) {
            // ========== 定时任务（使用 REST API 格式）==========
            // ActionList 只需要 ApiTag、SetValue、Delay
            val actions = listOf(mapOf(
                "ApiTag" to rule.cloudActuatorApiTag,
                "SetValue" to rule.cloudActionValue,
                "Delay" to 0
            ))

            // RunTimeList: Period=1 表示每日, 2=每周, 3=每月
            val runTimes = rule.runTimeSlots.map { slot ->
                mapOf(
                    "Period" to rule.runTimePeriod + 1,
                    "Day" to rule.runTimeDay,
                    "Time" to ""
                )
            }

            // 使用 GatewayID 作为 DeviceID（不是执行器的 DeviceID）
            val gatewayDeviceId = cloudDeviceIds.firstOrNull() ?: rule.cloudActuatorDeviceId
            addLog("GatewayID: $gatewayDeviceId")

            addLog("正在创建定时任务: ${rule.name} (${rule.runTimeSlots.size}个时间点)...")

            cloudManager.addStrategy(
                deviceId = gatewayDeviceId,
                kind = 1,
                expression = "",
                variables = emptyList(),
                actions = actions,
                runTimes = runTimes
            ) { strategyId ->
                mainHandler.post {
                    val rules = _rules.value ?: return@post
                    val idx = rules.indexOfFirst { it.id == rule.id }
                    if (idx == -1) return@post
                    val r = rules[idx]

                    if (strategyId != null) {
                        r.cloudStrategyId = strategyId
                        r.syncStatus = 1
                        r.retryCount = 0
                        r.enabled = false
                        addLog("定时任务创建成功: ${r.name} (ID=$strategyId)")
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

                    _rules.value = rules
                    saveRules()
                }
            }

        } else {
            // ========== 条件任务（使用 REST API Expression 格式）==========
            val expression = "${rule.cloudSensorApiTag} ${rule.operator} ${rule.threshold}"
            addLog("条件表达式: $expression")

            // ActionList 只需要 ApiTag、SetValue、Delay
            val actions = listOf(mapOf(
                "ApiTag" to rule.cloudActuatorApiTag,
                "SetValue" to rule.cloudActionValue,
                "Delay" to 0
            ))

            // RunTimeList: Period=1 表示每日
            val runTimes = listOf(mapOf(
                "Period" to 1,
                "Day" to 0
            ))

            // 使用 GatewayID 作为 DeviceID（不是传感器的 DeviceID）
            val gatewayDeviceId = cloudDeviceIds.firstOrNull() ?: rule.cloudSensorDeviceId
            addLog("GatewayID: $gatewayDeviceId")

            addLog("正在创建条件任务: ${rule.name}...")

            cloudManager.addStrategy(
                deviceId = gatewayDeviceId,
                kind = 1,
                expression = expression,
                variables = emptyList(),
                actions = actions,
                runTimes = runTimes
            ) { strategyId ->
                mainHandler.post {
                    val rules = _rules.value ?: return@post
                    val idx = rules.indexOfFirst { it.id == rule.id }
                    if (idx == -1) return@post
                    val r = rules[idx]

                    if (strategyId != null) {
                        r.cloudStrategyId = strategyId
                        r.syncStatus = 1
                        r.retryCount = 0
                        r.enabled = false
                        addLog("条件任务创建成功: ${r.name} (ID=$strategyId)")
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

                    _rules.value = rules
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

    /** 从云平台加载已有策略 */
    fun loadCloudStrategies() {
        if (!cloudManager.isConnected() || savedProjectId <= 0) return

        cloudManager.getStrategies(savedProjectId) { strategies ->
            mainHandler.post {
                val current = _rules.value ?: mutableListOf()
                current.removeAll { it.isCloud && it.syncStatus == 1 }

                var added = 0
                for (s in strategies) {
                    val strategyId = (s["StrategyId"] as? Number)?.toInt() ?: continue
                    val name = s["GatewayName"] as? String ?: "云策略"
                    val nullity = (s["Nullity"] as? Number)?.toInt() ?: 0
                    val variables = s["VariableList"] as? List<*> ?: emptyList<Any>()
                    val actions = s["ActionList"] as? List<*> ?: emptyList<Any>()
                    val runTimeList = s["RunTimeList"] as? List<*> ?: emptyList<Any>()

                    if (actions.isEmpty()) continue
                    if (current.any { it.cloudStrategyId == strategyId }) continue

                    val action = actions[0] as? Map<*, *> ?: continue
                    val actuatorDeviceId = (action["GatewayDeviceID"] as? Number)?.toInt() ?: -1
                    val actuatorApiTag = action["ApiTag"] as? String ?: ""
                    val setValue = action["SetValue"] as? String ?: "1"
                    val actuatorName = sensorMetaCache["$actuatorDeviceId:$actuatorApiTag"]?.Name ?: actuatorApiTag

                    val isTimedTask = runTimeList.isNotEmpty() && variables.isEmpty()

                    if (isTimedTask) {
                        // 解析所有定时条目
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

                    } else if (variables.isNotEmpty()) {
                        val variable = variables[0] as? Map<*, *> ?: continue
                        val sensorDeviceId = (variable["GatewayDeviceID"] as? Number)?.toInt() ?: -1
                        val sensorApiTag = variable["ApiTag"] as? String ?: ""
                        val operatorInt = when (val op = variable["Operator"]) {
                            is Number -> op.toInt()
                            is String -> op.toIntOrNull() ?: 1
                            else -> 1
                        }
                        val rightValue = when (val rv = variable["RightValue"]) {
                            is Number -> rv.toFloat()
                            is String -> rv.toFloatOrNull() ?: 0f
                            else -> 0f
                        }
                        val sensorName = sensorMetaCache["$sensorDeviceId:$sensorApiTag"]?.Name ?: sensorApiTag

                        val rule = Rule(
                            name = name,
                            operator = intToOperator(operatorInt),
                            threshold = rightValue,
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
                    }
                }

                _rules.value = current
                saveRules()
                addLog("已从云平台加载 $added 条策略（共 ${strategies.size} 条）")
            }
        }
    }

    private fun cancelPendingCallbacks(ruleId: Long) {
        pendingTimeouts.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
        retryRunnables.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
    }

    // ========== 传感器告警 ==========

    private fun checkSensorAlerts(data: SensorData) {
        if (!prefs.getBoolean("notif_enabled", true)) return
        val now = System.currentTimeMillis()

        if (prefs.getBoolean("notif_temp_enabled", false)) {
            val high = prefs.getFloat("notif_temp_high", -999f)
            val low = prefs.getFloat("notif_temp_low", -999f)
            if (!high.isNaN() && high != -999f && data.temp > high && canAlert("temp_high", now)) {
                notifHelper.sendTempAlert(data.temp, high, true)
                markAlerted("temp_high", now)
            }
            if (!low.isNaN() && low != -999f && data.temp < low && canAlert("temp_low", now)) {
                notifHelper.sendTempAlert(data.temp, low, false)
                markAlerted("temp_low", now)
            }
        }

        if (prefs.getBoolean("notif_humi_enabled", false)) {
            val high = prefs.getFloat("notif_humi_high", -999f)
            val low = prefs.getFloat("notif_humi_low", -999f)
            if (!high.isNaN() && high != -999f && data.humi > high && canAlert("humi_high", now)) {
                notifHelper.sendHumiAlert(data.humi, high, true)
                markAlerted("humi_high", now)
            }
            if (!low.isNaN() && low != -999f && data.humi < low && canAlert("humi_low", now)) {
                notifHelper.sendHumiAlert(data.humi, low, false)
                markAlerted("humi_low", now)
            }
        }

        if (prefs.getBoolean("notif_light_enabled", false)) {
            val high = prefs.getInt("notif_light_high", -999)
            val low = prefs.getInt("notif_light_low", -999)
            if (high != -999 && data.light > high && canAlert("light_high", now)) {
                notifHelper.sendLightAlert(data.light, high, true)
                markAlerted("light_high", now)
            }
            if (low != -999 && data.light < low && canAlert("light_low", now)) {
                notifHelper.sendLightAlert(data.light, low, false)
                markAlerted("light_low", now)
            }
        }

        if (prefs.getBoolean("notif_pir_enabled", false)) {
            val lastPir = prefs.getInt("last_pir_state", -1)
            if (data.pir != lastPir && canAlert("pir", now)) {
                notifHelper.sendPirAlert(data.pir == 1)
                markAlerted("pir", now)
                prefs.edit().putInt("last_pir_state", data.pir).apply()
            }
        }

        if (prefs.getBoolean("notif_flame_enabled", false)) {
            if (data.flame == 1 && canAlert("flame", now)) {
                notifHelper.sendFlameAlert(data.flame == 1)
                markAlerted("flame", now)
                prefs.edit().putInt("last_flame_state", data.flame).apply()
            }
        }

        if (prefs.getBoolean("notif_gas_enabled", false)) {
            val gasThreshold = prefs.getInt("notif_gas_threshold", 400)
            val lastGasState = prefs.getInt("last_gas_alert_state", -1)
            val currentGasState = if (data.gas >= gasThreshold) 1 else 0
            if (currentGasState != lastGasState && canAlert("gas", now)) {
                notifHelper.sendGasAlert(data.gas, gasThreshold, currentGasState == 1)
                markAlerted("gas", now)
                prefs.edit().putInt("last_gas_alert_state", currentGasState).apply()
            }
        }
    }

    private fun canAlert(key: String, now: Long): Boolean {
        val last = lastAlertTime[key] ?: 0L
        return (now - last) > ALERT_COOLDOWN
    }

    private fun markAlerted(key: String, now: Long) {
        lastAlertTime[key] = now
    }

    // ========== 条件任务检查（双模式）==========

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
            if (rule.isTimedTask) continue  // ★ 定时任务由云平台调度，本地不检查

            val sensorVal: Double = if (rule.isCloud) {
                val values = _cloudSensorValues.value ?: emptyMap()
                val key = "${rule.cloudSensorDeviceId}:${rule.cloudSensorApiTag}"
                values[key]?.toDoubleOrNull() ?: continue
            } else {
                sensorMap[rule.sensorType] ?: continue
            }

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
                markAlerted("rule_${rule.id}", now)

                if (rule.isCloud && cloudManager.isConnected()) {
                    cloudManager.sendCommand(
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
                    cm.sendCommand(rule.actionDevice, rule.actionCmd)
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
                notifHelper.sendRuleTriggered(rule.name, desc)
                addLog("任务触发: ${rule.name}")
            }
        }
    }

    // ========== 公开方法 ==========

    fun connectWifi(host: String, port: Int) {
        prefs.edit()
            .putString("conn_mode", "wifi")
            .putString("wifi_host", host)
            .putInt("wifi_port", port)
            .apply()
        cm.connectWifi(host, port)
    }

    fun connectBluetooth(device: BluetoothDevice) { cm.connectBluetooth(device) }

    fun connectCloud(cm: NleCloudManager) {
        _cloudLoginState.value = true
        _connectionStatus.value = "云平台已连接"
        addLog("云平台 HTTP 连接成功")
        // 连接成功后立即刷新设备并启动轮询
        refreshCloudDevices()
    }

    private fun parseMqttPayload(payload: String) {
        try {
            val map = gson.fromJson(payload, Map::class.java) ?: return

            val datas = map["datas"] as? Map<*, *>
            if (datas != null) {
                val devId = (map["devId"] as? Number)?.toInt()
                    ?: (map["DeviceID"] as? Number)?.toInt()
                applyMqttSensorData(devId, datas)
                return
            }

            val keys = listOf("temp", "humi", "light", "pir", "flame", "gas",
                "temperature", "humidity", "luminance")
            if (keys.any { map.containsKey(it) }) {
                applyMqttSensorData(null, map)
                return
            }

            addLog("MQTT 收到: $payload")
        } catch (e: Exception) {
            Log.e("MainVM", "MQTT parse error", e)
            addLog("MQTT 数据解析失败")
        }
    }

    private fun applyMqttSensorData(devId: Int?, datas: Map<*, *>) {
        val values = (_cloudSensorValues.value ?: emptyMap()).toMutableMap()

        for ((key, value) in datas) {
            val tag = key.toString()
            val v = value.toString()
            val mapKey = if (devId != null) "$devId:$tag" else tag
            values[mapKey] = v
        }

        _cloudSensorValues.value = values
        updateSensorDataFromMap(values)
        addLog("MQTT 数据已更新 (${datas.size} 项)")
    }

    private fun updateSensorDataFromMap(values: Map<String, String>) {
        var temp = 0f; var humi = 0f; var light = 0
        var pir = 0; var flame = 0; var gas = 0

        for ((key, v) in values) {
            val tag = key.lowercase()
            when {
                "temp" in tag  -> temp = v.toFloatOrNull() ?: 0f
                "humi" in tag  -> humi = v.toFloatOrNull() ?: 0f
                "light" in tag -> light = v.toIntOrNull() ?: 0
                "pir" in tag   -> pir = v.toIntOrNull() ?: 0
                "flame" in tag -> flame = v.toIntOrNull() ?: 0
                "gas" in tag   -> gas = v.toIntOrNull() ?: 0
            }
        }

        val data = SensorData(
            temp = temp, humi = humi, light = light,
            pir = pir, flame = flame, gas = gas
        )
        _sensorData.value = data
        checkSensorAlerts(data)
        checkRules(data)
    }

    // ========== 云平台设备刷新 ==========

    fun refreshCloudDevices() {
        val cm = cloudManager

        if (!cm.isConnected() || savedProjectId <= 0) {
            val devices = _sensorDevices.value
            if (devices != null) {
                val removed = devices.removeAll { it.isCloud }
                if (removed) {
                    _sensorDevices.value = devices
                    saveSensorDevices()
                }
            }
            val switches = _switches.value
            if (switches != null) {
                val removed = switches.removeAll { it.isCloud }
                if (removed) {
                    _switches.value = switches
                    saveSwitches()
                }
            }
            addLog("云平台未连接，已移除云端卡片")
            return
        }

        addLog("正在刷新云平台设备...")
        stopCloudPolling()

        val projectId = savedProjectId

        cm.getDevicesByProject(projectId) { devices ->
            if (devices.isEmpty()) {
                mainHandler.post { addLog("获取设备列表为空") }
                return@getDevicesByProject
            }

            cloudDeviceIds = devices.map { it.DeviceID }
            savedDeviceList = devices

            cm.getProjectSensors(projectId) { allSensors ->
                val sensorsOnly = allSensors.filter { !it.isActuator }
                val actuatorsOnly = allSensors.filter { it.isActuator }

                val cache = mutableMapOf<String, SensorPoint>()
                for (s in allSensors) {
                    cache["${s.DeviceID}:${s.ApiTag}"] = s
                }

                // 从每个设备获取传感器详情（包括 Unit 字段）
                fetchDeviceSensorsWithUnit(devices.map { it.DeviceID }) { deviceSensors ->
                    // 合并设备传感器详情到缓存
                    for ((key, sensor) in deviceSensors) {
                        if (cache[key] == null || cache[key]?.Unit?.isBlank() == true) {
                            cache[key] = sensor
                        }
                    }

                    val devIds = cloudDeviceIds.joinToString(",")
                    cm.getSensors(devIds) { dataPoints ->
                        mainHandler.post {
                            sensorMetaCache = cache
                            addLog("刷新完成：传感器 ${sensorsOnly.size} 个，执行器 ${actuatorsOnly.size} 个")

                            val sensorDataPoints = dataPoints.filter { dp ->
                                val meta = cache["${dp.DeviceID}:${dp.ApiTag}"]
                                meta == null || !meta.isActuator
                            }
                            createCloudSensorCards(sensorDataPoints)
                            createCloudActuatorCards(actuatorsOnly, dataPoints)

                            addLog("卡片创建完成，cloudDeviceIds=${cloudDeviceIds}")

                            // ★ 同步加载云策略
                            loadCloudStrategies()

                            startStrategyPolling()

                            // 启动传感器轮询
                            startCloudPolling()
                        }
                    }
                }
            }
        }
    }

    fun setupCloudDevices(devices: List<DeviceBaseInfo>, projectId: Int) {
        val cm = cloudManager
        cloudDeviceIds = devices.map { it.DeviceID }

        savedDeviceList = devices
        savedProjectId = projectId
        _cloudConnected.value = true

        // 保存项目设置到 SharedPreferences
        saveProjectSettings()

        addLog("正在获取传感器元数据...")

        cm.getProjectSensors(projectId) { allSensors ->
            val sensorsOnly = allSensors.filter { !it.isActuator }
            val actuatorsOnly = allSensors.filter { it.isActuator }

            val cache = mutableMapOf<String, SensorPoint>()
            for (s in allSensors) {
                cache["${s.DeviceID}:${s.ApiTag}"] = s
            }

            // 从每个设备获取传感器详情（包括 Unit 字段）
            fetchDeviceSensorsWithUnit(devices.map { it.DeviceID }) { deviceSensors ->
                // 合并设备传感器详情到缓存
                for ((key, sensor) in deviceSensors) {
                    if (cache[key] == null || cache[key]?.Unit?.isBlank() == true) {
                        cache[key] = sensor
                    }
                }

                val devIds = cloudDeviceIds.joinToString(",")
                cm.getSensors(devIds) { dataPoints ->
                    mainHandler.post {
                        sensorMetaCache = cache
                        addLog("传感器 ${sensorsOnly.size} 个，执行器 ${actuatorsOnly.size} 个")

                        val sensorDataPoints = dataPoints.filter { dp ->
                            val meta = cache["${dp.DeviceID}:${dp.ApiTag}"]
                            meta == null || !meta.isActuator
                        }
                        createCloudSensorCards(sensorDataPoints)
                        createCloudActuatorCards(actuatorsOnly, dataPoints)

                        addLog("卡片创建完成")

                        // ★ 加载云策略
                        loadCloudStrategies()

                        startStrategyPolling()

                        // 启动传感器轮询
                        startCloudPolling()
                    }
                }
            }
        }
    }

    private fun createCloudActuatorCards(
        actuators: List<SensorPoint>,
        dataPoints: List<SensorPoint>
    ) {
        val currentSwitches = _switches.value ?: mutableListOf()
        currentSwitches.removeAll { it.isCloud }

        val valueMap = mutableMapOf<String, String>()
        // ★ 构建 ApiTag → 正确DeviceID 的映射
        val apiTagToDeviceId = mutableMapOf<String, Int>()
        for (dp in dataPoints) {
            valueMap["${dp.DeviceID}:${dp.ApiTag}"] = dp.Value
            apiTagToDeviceId[dp.ApiTag] = dp.DeviceID
        }

        for (act in actuators) {
            // ★ 用 dataPoints 中的设备ID，不用 getProjectSensors 的
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
                cloudDeviceId = correctDeviceId,    // ★ 正确的设备ID
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
            "fan" in tag || "风扇" in n || "风机" in n -> "🌀"
            "light" in tag || "灯" in n || "led" in tag -> "💡"
            "door" in tag || "门" in n -> "🚪"
            "motor" in tag || "电机" in n -> "⚡"
            "pump" in tag || "泵" in n -> "💧"
            "valve" in tag || "阀" in n -> "🔧"
            "relay" in tag || "继电器" in n -> "🔌"
            "alarm" in tag || "警报" in n -> "🔊"
            operType == 1 -> "💡"
            operType == 3 -> "🔘"
            operType == 4 -> "🎚"
            else -> "⚡"
        }
    }

    private fun createCloudSensorCards(dataPoints: List<SensorPoint>) {
        val current = _sensorDevices.value ?: mutableListOf()
        current.removeAll { it.isCloud }

        for (dp in dataPoints) {
            val cacheKey = "${dp.DeviceID}:${dp.ApiTag}"
            val meta = sensorMetaCache[cacheKey]

            val displayName = meta?.Name?.takeIf { it.isNotBlank() } ?: dp.ApiTag
            // 优先使用 API 返回的 Unit，如果没有则根据 ApiTag 推断
            val unit = meta?.Unit?.takeIf { it.isNotBlank() }
                ?: guessSensorUnit(dp.ApiTag, displayName)
            val icon = guessSensorIcon(dp.ApiTag, displayName)

            // 匹配本地传感器类型，用于使用本地卡片样式
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

        _sensorDevices.value = current
        saveSensorDevices()
    }

    private fun guessSensorIcon(apiTag: String, name: String): String {
        val tag = apiTag.lowercase()
        val n = name.lowercase()
        return when {
            "temp" in tag || "温度" in n     -> "🌡"
            "humi" in tag || "湿度" in n     -> "💧"
            "light" in tag || "光照" in n || "亮度" in n -> "☀"
            "pir" in tag || "人体" in n || "红外" in n   -> "○"
            "flame" in tag || "火" in n      -> "🔥"
            "gas" in tag || "燃气" in n || "烟雾" in n   -> "💨"
            "wind" in tag || "风速" in n     -> "🌀"
            "co2" in tag || "二氧化碳" in n  -> "🫁"
            "pm" in tag || "粉尘" in n       -> "🌫"
            else -> "📡"
        }
    }

    /** 根据 ApiTag 推断传感器单位 */
    private fun guessSensorUnit(apiTag: String, name: String): String {
        val tag = apiTag.lowercase()
        val n = name.lowercase()
        return when {
            "temp" in tag || "温度" in n     -> "°C"
            "humi" in tag || "湿度" in n     -> "%RH"
            "light" in tag || "光照" in n || "亮度" in n -> "lux"
            "pir" in tag || "人体" in n || "红外" in n   -> ""
            "flame" in tag || "火" in n      -> ""
            "gas" in tag || "燃气" in n || "烟雾" in n   -> "ppm"
            "wind" in tag || "风速" in n     -> "m/s"
            "co2" in tag || "二氧化碳" in n  -> "ppm"
            "pm" in tag || "粉尘" in n       -> "μg/m³"
            "wendu" in tag -> "°C"  // 用户自定义的温度传感器
            "shidu" in tag -> "%RH"  // 用户自定义的湿度传感器
            "nl_fan" in tag -> ""  // 风扇执行器
            else -> ""
        }
    }

    private fun startCloudPolling() {
        stopCloudPolling()
        if (cloudDeviceIds.isNotEmpty()) {
            mainHandler.post { addLog("开始传感器轮询，设备: $cloudDeviceIds") }
            pollCloudSensors()
            cloudPollTimer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() { pollCloudSensors() }
                }, 2000L, 2000L)
            }
        }
    }

    private fun pollCloudSensors() {
        val cm = cloudManager ?: return
        if (cloudDeviceIds.isEmpty()) return

        val devIds = cloudDeviceIds.joinToString(",")

        cm.getSensors(devIds) { sensors ->
            mainHandler.post {
                val values = mutableMapOf<String, String>()
                for (sensor in sensors) {
                    values["${sensor.DeviceID}:${sensor.ApiTag}"] = sensor.Value
                }
                _cloudSensorValues.value = values
                updateSensorDataFromCloud(sensors)

                // 同步更新执行器卡片状态
                updateActuatorStatesFromCloud(sensors)

                if (sensors.isNotEmpty()) {
                    addLog("传感器更新: ${sensors.size} 项")
                }
            }
        }
    }

    /** 从云平台数据更新执行器卡片的开关状态 */
    private fun updateActuatorStatesFromCloud(sensors: List<SensorPoint>) {
        val switches = _switches.value ?: return
        var changed = false

        val cloudActuatorTags = switches.filter { it.isCloud }.map { it.cloudApiTag }.toSet()

        for (sensor in sensors) {
            if (sensor.ApiTag !in cloudActuatorTags) continue

            val sw = switches.firstOrNull {
                it.isCloud && it.cloudApiTag == sensor.ApiTag
            } ?: continue

            val rawValue = sensor.Value.trim()
            val newValue = when (rawValue) {
                "1", "1.0", "true", "True", "TRUE" -> true
                "0", "0.0", "false", "False", "FALSE" -> false
                else -> continue
            }

            if (sw.isOn != newValue) {
                sw.isOn = newValue
                changed = true
                addLog("执行器状态更新: ${sw.name} ($rawValue) → ${if (newValue) "开启" else "关闭"}")
            } else {
                addLog("执行器状态不变: ${sw.name} = ${if (sw.isOn) "开启" else "关闭"}")
            }
        }

        if (changed) {
            _switches.value = switches
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
        checkSensorAlerts(data)
        checkRules(data)
    }

    private fun stopCloudPolling() {
        cloudPollTimer?.cancel()
        cloudPollTimer = null
    }

    // ========== 策略状态轮询 ==========

    private fun startStrategyPolling() {
        stopStrategyPolling()
        // 立即执行一次策略状态同步
        pollStrategyEnabledStatus()
        // 启动定时轮询（每10秒）
        strategyPollTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    pollStrategyEnabledStatus()
                }
            }, 10_000L, 10_000L)//30秒查询一次更新，减少资源占用
        }
    }

    private fun stopStrategyPolling() {
        strategyPollTimer?.cancel()
        strategyPollTimer = null
    }

    /** 从云平台获取策略状态，仅更新 enabled 字段 */
    private fun pollStrategyEnabledStatus() {
        if (!cloudManager.isConnected() || savedProjectId <= 0) return

        cloudManager.getStrategies(savedProjectId) { strategies ->
            mainHandler.post {
                val rules = _rules.value ?: return@post
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
                    _rules.value = rules
                    saveRules()
                }
            }
        }
    }

    // ========== 退出 / 断开 ==========

    fun logoutCloud() {
        stopCloudPolling()
        stopStrategyPolling()

        cloudDeviceIds = emptyList()
        _cloudSensorValues.value = emptyMap()

        val devices = _sensorDevices.value
        if (devices != null) {
            devices.removeAll { it.isCloud }
            _sensorDevices.value = devices
            saveSensorDevices()
        }

        val switches = _switches.value
        if (switches != null) {
            switches.removeAll { it.isCloud }
            _switches.value = switches
            saveSwitches()
        }

        val rules = _rules.value
        if (rules != null) {
            rules.removeAll { it.isCloud }
            _rules.value = rules
            saveRules()
        }

        cloudManager.disconnect()
        _cloudLoginState.value = false
        _cloudConnected.value = false
        savedProjectList = emptyList()
        savedProjectId = -1
        savedProjectName = ""
        savedDeviceList = emptyList()

        // 清除保存的项目设置和凭证
        prefs.edit()
            .remove("cloud_project_id")
            .remove("cloud_project_name")
            .apply()
        // 清除云平台凭证
        val nlePrefs = getApplication<Application>()
            .getSharedPreferences("nlecloud_prefs", Context.MODE_PRIVATE)
        nlePrefs.edit().clear().apply()

        _connectionStatus.value = "未连接"
        addLog("已退出云平台登录")
    }

    fun disconnectCloudOnly() {
        stopCloudPolling()
        stopStrategyPolling()

        cloudDeviceIds = emptyList()
        _cloudSensorValues.value = emptyMap()

        val devices = _sensorDevices.value
        if (devices != null) {
            devices.removeAll { it.isCloud }
            _sensorDevices.value = devices
            saveSensorDevices()
        }

        val switches = _switches.value
        if (switches != null) {
            switches.removeAll { it.isCloud }
            _switches.value = switches
            saveSwitches()
        }

        val rules = _rules.value
        if (rules != null) {
            rules.removeAll { it.isCloud }
            _rules.value = rules
            saveRules()
        }

        _cloudConnected.value = false
        savedDeviceList = emptyList()
        savedProjectId = -1
        savedProjectName = ""

        // 清除保存的项目设置
        prefs.edit()
            .remove("cloud_project_id")
            .remove("cloud_project_name")
            .apply()

        _connectionStatus.value = "未连接"
        addLog("云平台连接已断开")
    }

    fun disconnect() {
        stopStatusPolling()
        cm.disconnect()
    }

    fun toggleLight() {
        val newState = !(_lightState.value ?: false)
        cm.sendCommand("light", if (newState) "on" else "off")
        _lightState.value = newState
    }

    fun toggleFan() {
        val newState = !(_fanState.value ?: false)
        cm.sendCommand("fan", if (newState) "on" else "off")
        _fanState.value = newState
    }

    // ========== 规则 CRUD（双模式路由）==========

    fun addRule(rule: Rule) {
        val list = _rules.value ?: mutableListOf()
        list.add(rule)
        _rules.value = list
        saveRules()

        if (rule.isCloud) {
            attemptCloudRuleSync(list.size - 1)
        } else {
            attemptRuleSync(list.size - 1)
        }
    }

    fun removeRule(index: Int) {
        val list = _rules.value ?: return
        if (index !in list.indices) return
        val rule = list[index]

        cancelPendingCallbacks(rule.id)

        if (rule.isCloud && rule.cloudStrategyId > 0) {
            cloudManager.deleteStrategy(listOf(rule.cloudStrategyId)) { success ->
                mainHandler.post {
                    addLog(if (success) "云策略已删除: ${rule.name}"
                    else "云策略删除失败: ${rule.name}")
                }
            }
        }

        list.removeAt(index)
        _rules.value = list
        saveRules()
    }

    fun toggleRule(index: Int) {
        val list = _rules.value ?: return
        if (index !in list.indices) return
        val rule = list[index]

        rule.enabled = !rule.enabled
        _rules.value = list
        saveRules()

        if (rule.isCloud && rule.cloudStrategyId > 0) {
            cloudManager.enableStrategy(rule.cloudStrategyId, rule.enabled) { success ->
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
        val list = _rules.value ?: return
        if (index !in list.indices) return
        list[index].retryCount = 0
        list[index].syncStatus = 0
        _rules.value = list
        saveRules()

        if (list[index].isCloud) {
            attemptCloudRuleSync(index)
        } else {
            attemptRuleSync(index)
        }
    }

    /**
     * 从每个设备获取传感器详情（包括 Unit 字段）
     * 返回 Map: "DeviceID:ApiTag" → SensorPoint
     */
    private fun fetchDeviceSensorsWithUnit(
        deviceIds: List<Int>,
        callback: (Map<String, SensorPoint>) -> Unit
    ) {
        val result = mutableMapOf<String, SensorPoint>()
        val queue = deviceIds.toMutableList()

        fun fetchNext() {
            if (queue.isEmpty()) {
                callback(result)
                return
            }
            val devId = queue.removeAt(0)
            cloudManager.getDeviceSensors(devId) { sensors ->
                for (s in sensors) {
                    result["${s.DeviceID}:${s.ApiTag}"] = s
                }
                fetchNext()
            }
        }

        fetchNext()
    }

    // ========== 云策略辅助 ==========

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

    // ========== 传感器卡片管理 ==========

    fun addSensorDevice(item: SensorDeviceItem) {
        val list = _sensorDevices.value ?: mutableListOf()
        list.add(item)
        _sensorDevices.value = list
        saveSensorDevices()
    }

    fun removeSensorDevice(id: Long) {
        val list = _sensorDevices.value ?: return
        val removed = list.removeAll { it.id == id }
        if (removed) {
            _sensorDevices.value = list
            saveSensorDevices()
        }
    }

    private fun getDefaultSensorDevices(): List<SensorDeviceItem> {
        return listOf(
            SensorDeviceItem(icon = "🌡", name = "温度",       sensorType = "temp",  unit = "°C"),
            SensorDeviceItem(icon = "💧", name = "湿度",       sensorType = "humi",  unit = "%RH"),
            SensorDeviceItem(icon = "☀",  name = "光照强度",   sensorType = "light", unit = "lux"),
            SensorDeviceItem(icon = "○",  name = "人体检测",   sensorType = "pir",   unit = ""),
            SensorDeviceItem(icon = "💨", name = "可燃气浓度", sensorType = "gas",   unit = ""),
            SensorDeviceItem(icon = "🔥", name = "火焰检测",   sensorType = "flame", unit = "")
        )
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

    private fun saveSensorDevices() {
        val json = gson.toJson(_sensorDevices.value ?: emptyList<SensorDeviceItem>())
        prefs.edit().putString("sensor_devices", json).apply()
    }

    private fun loadSensorDevices() {
        val json = prefs.getString("sensor_devices", null)
        if (json != null) {
            try {
                val type = object : TypeToken<MutableList<SensorDeviceItem>>() {}.type
                _sensorDevices.value = gson.fromJson(json, type)
            } catch (_: Exception) {
                _sensorDevices.value = getDefaultSensorDevices().toMutableList()
            }
        } else {
            _sensorDevices.value = getDefaultSensorDevices().toMutableList()
        }
    }

    // ========== 开关管理 ==========

    fun addSwitch(sw: ControlSwitch) {
        val list = _switches.value ?: mutableListOf()
        list.add(sw)
        _switches.value = list
        saveSwitches()
    }

    fun updateSwitch(index: Int, sw: ControlSwitch) {
        val list = _switches.value ?: return
        if (index in list.indices) {
            list[index] = sw
            _switches.value = list
            saveSwitches()
        }
    }

    fun removeSwitch(index: Int) {
        val list = _switches.value ?: return
        if (index in list.indices) {
            list.removeAt(index)
            _switches.value = list
            saveSwitches()
        }
    }

    fun toggleSwitch(index: Int) {
        val list = _switches.value ?: return
        if (index !in list.indices) return
        val sw = list[index]
        val newIsOn = !sw.isOn

        if (sw.isCloud) {
            val value = if (newIsOn) "1" else "0"
            cloudManager.sendCommand(sw.cloudDeviceId, sw.cloudApiTag, value) { success ->
                mainHandler.post {
                    if (success) {
                        sw.isOn = newIsOn
                        _switches.value = list
                        saveSwitches()
                        addLog("云命令成功: ${sw.name} → ${if (newIsOn) "开启" else "关闭"}")
                    } else {
                        addLog("云命令失败: ${sw.name}")
                    }
                }
            }
        } else {
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            cm.sendRawCommand(cmd)
            sw.isOn = newIsOn
            _switches.value = list
            saveSwitches()
        }
    }

    fun toggleSwitchSilent(index: Int) {
        val list = _switches.value ?: return
        if (index !in list.indices) return
        val sw = list[index]
        val newIsOn = !sw.isOn

        if (sw.isCloud) {
            // 立即更新 UI
            sw.isOn = newIsOn
            _switches.value = list
            saveSwitches()

            // HTTP 发送命令
            val value = if (newIsOn) "1" else "0"
            cloudManager.sendCommand(sw.cloudDeviceId, sw.cloudApiTag, value) { success ->
                mainHandler.post {
                    if (!success) {
                        sw.isOn = !newIsOn
                        _switches.value = list
                        saveSwitches()
                        addLog("云命令失败: ${sw.name}，已回滚")
                    }
                }
            }
        } else {
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            cm.sendRawCommand(cmd)
            sw.isOn = newIsOn
            _switches.value = list
            saveSwitches()
        }
    }

    // ========== 通知设置 ==========

    fun saveNotifSettings(settings: Map<String, Any>) {
        val editor = prefs.edit()
        for ((key, value) in settings) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    fun getNotifSettings(): Map<String, Any> {
        return mapOf(
            "notif_enabled" to prefs.getBoolean("notif_enabled", true),
            "notif_temp_enabled" to prefs.getBoolean("notif_temp_enabled", false),
            "notif_temp_high" to prefs.getFloat("notif_temp_high", -999f),
            "notif_temp_low" to prefs.getFloat("notif_temp_low", -999f),
            "notif_humi_enabled" to prefs.getBoolean("notif_humi_enabled", false),
            "notif_humi_high" to prefs.getFloat("notif_humi_high", -999f),
            "notif_humi_low" to prefs.getFloat("notif_humi_low", -999f),
            "notif_light_enabled" to prefs.getBoolean("notif_light_enabled", false),
            "notif_light_high" to prefs.getInt("notif_light_high", -999),
            "notif_light_low" to prefs.getInt("notif_light_low", -999),
            "notif_pir_enabled" to prefs.getBoolean("notif_pir_enabled", false),
            "notif_rule_enabled" to prefs.getBoolean("notif_rule_enabled", true),
            "notif_flame_enabled" to prefs.getBoolean("notif_flame_enabled", false),
            "notif_gas_enabled" to prefs.getBoolean("notif_gas_enabled", false),
            "notif_gas_threshold" to prefs.getInt("notif_gas_threshold", 400)
        )
    }

    fun testNotification() {
        notifHelper.sendNotification("🔔 通知测试", "智能家居通知功能正常工作！", 9999)
    }

    fun sendRawCmd(json: String) {
        cm.sendRawCommand(json)
        addLog("手动发送: $json")
    }

    fun clearLogs() {
        _logMessages.value = mutableListOf()
    }

    // ========== 私有方法 ==========

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

    private fun addLog(msg: String) {
        val list = _logMessages.value ?: mutableListOf()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        list.add(0, "[$time] $msg")
        if (list.size > 100) list.removeAt(list.lastIndex)
        _logMessages.value = list
    }

    private fun saveRules() {
        val json = gson.toJson(_rules.value ?: emptyList<Rule>())
        prefs.edit().putString("rules", json).apply()
    }

    private fun loadRules() {
        val json = prefs.getString("rules", null)
        if (json != null) {
            try {
                val type = object : TypeToken<MutableList<Rule>>() {}.type
                _rules.value = gson.fromJson(json, type)
            } catch (_: Exception) {
                _rules.value = mutableListOf()
            }
        } else {
            _rules.value = mutableListOf()
        }
    }

    private fun saveSwitches() {
        val json = gson.toJson(_switches.value ?: emptyList<ControlSwitch>())
        prefs.edit().putString("switches", json).apply()
    }

    private fun loadSwitches() {
        val json = prefs.getString("switches", null)
        if (json != null) {
            try {
                val type = object : TypeToken<MutableList<ControlSwitch>>() {}.type
                _switches.value = gson.fromJson(json, type)
            } catch (_: Exception) {
                _switches.value = mutableListOf()
            }
        } else {
            _switches.value = mutableListOf()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStatusPolling()
        mainHandler.removeCallbacksAndMessages(null)
        cm.disconnect()
    }

    fun saveProjectList(list: List<ProjectInfo>) {
        savedProjectList = list
    }

    fun getSavedProjectList(): List<ProjectInfo> = savedProjectList

    fun getSavedProjectId(): Int = savedProjectId

    fun getSavedProjectName(): String = savedProjectName

    fun saveProjectName(name: String) {
        savedProjectName = name
    }

    fun getSavedDeviceList(): List<DeviceBaseInfo> = savedDeviceList

    // ========== 项目设置持久化 ==========

    private fun saveProjectSettings() {
        prefs.edit()
            .putInt("cloud_project_id", savedProjectId)
            .putString("cloud_project_name", savedProjectName)
            .apply()
    }

    private fun loadProjectSettings() {
        savedProjectId = prefs.getInt("cloud_project_id", -1)
        savedProjectName = prefs.getString("cloud_project_name", "") ?: ""
    }

    // ========== 自动登录 ==========

    /**
     * 启动时自动登录云平台
     * 如果有保存的凭证，尝试登录并加载数据
     * 如果没有凭证或登录失败，移除云平台卡片
     */
    fun autoLogin() {
        val (account, password) = cloudManager.getSavedCredentials()

        if (account.isBlank() || password.isBlank()) {
            addLog("无保存的云平台凭证，跳过自动登录")
            removeCloudCards()
            return
        }

        addLog("正在自动登录云平台...")
        _cloudLoginState.value = true

        cloudManager.login(account, password) { success, msg ->
            mainHandler.post {
                if (success) {
                    addLog("云平台自动登录成功")
                    loadProjectSettings()

                    if (savedProjectId > 0) {
                        // 有保存的项目，直接连接
                        connectToSavedProject()
                    } else {
                        // 没有保存的项目，获取项目列表
                        loadProjectsAndConnect()
                    }
                } else {
                    addLog("云平台自动登录失败: $msg")
                    _cloudLoginState.value = false
                    removeCloudCards()
                }
            }
        }
    }

    /**
     * 连接到保存的项目
     */
    private fun connectToSavedProject() {
        addLog("正在连接项目: $savedProjectName (ID=$savedProjectId)")

        cloudManager.connectToProject(savedProjectId) { success ->
            mainHandler.post {
                if (success) {
                    _cloudConnected.value = true
                    _connectionStatus.value = "云平台已连接 - $savedProjectName"
                    addLog("项目连接成功: $savedProjectName")
                    // 先刷新设备，完成后自动开始轮询
                    refreshCloudDevices()
                } else {
                    addLog("项目连接失败")
                    _cloudConnected.value = false
                    _connectionStatus.value = "未连接"
                    removeCloudCards()
                }
            }
        }
    }

    /**
     * 获取项目列表并连接第一个项目
     */
    private fun loadProjectsAndConnect() {
        cloudManager.getProjects { projects ->
            mainHandler.post {
                if (projects.isEmpty()) {
                    addLog("未找到云平台项目")
                    _cloudLoginState.value = false
                    removeCloudCards()
                } else {
                    savedProjectList = projects
                    val project = projects.first()
                    savedProjectId = project.ProjectID
                    savedProjectName = project.Name
                    saveProjectSettings()

                    addLog("找到 ${projects.size} 个项目，自动连接: ${project.Name}")
                    connectToSavedProject()
                }
            }
        }
    }

    /**
     * 移除所有云平台创建的卡片
     */
    private fun removeCloudCards() {
        // 移除云传感器卡片
        val devices = _sensorDevices.value
        if (devices != null) {
            val removed = devices.removeAll { it.isCloud }
            if (removed) {
                _sensorDevices.value = devices
                saveSensorDevices()
            }
        }

        // 移除云执行器卡片
        val switches = _switches.value
        if (switches != null) {
            val removed = switches.removeAll { it.isCloud }
            if (removed) {
                _switches.value = switches
                saveSwitches()
            }
        }

        // 移除云策略
        val rules = _rules.value
        if (rules != null) {
            val removed = rules.removeAll { it.isCloud }
            if (removed) {
                _rules.value = rules
                saveRules()
            }
        }

        addLog("已移除云平台卡片")
    }
}
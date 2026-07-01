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
import com.google.gson.reflect.TypeToken
import com.smarthome.app.model.ControlSwitch
import com.smarthome.app.model.Rule
import com.smarthome.app.model.SensorData
import com.smarthome.app.network.ConnectionManager
import com.smarthome.app.notification.NotificationHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("smart_home_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
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
                    startStatusPolling()       // ★ 新增：连接成功后启动轮询
                }
                ConnectionManager.MSG_DISCONNECTED -> {
                    _connectionStatus.value = "未连接"
                    addLog(msg.obj as String)
                    stopStatusPolling()        // ★ 新增：断开时停止轮询
                }
                ConnectionManager.MSG_SENSOR_DATA -> {
                    val data = msg.obj as SensorData
                    _sensorData.value = data
                    checkSensorAlerts(data)
                    checkRules(data)
                }
                ConnectionManager.MSG_CONTROL_ACK -> {
                    val json = msg.obj as String
                    // ★ 新增：先尝试解析为状态响应
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
                    // ★ 新增：日志中也可能携带状态响应
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
    }


    // ========== 纠错：只翻转本地开关状态，不发送任何指令 ==========

    fun correctSwitchState(index: Int) {
        val list = _switches.value ?: return
        if (index !in list.indices) return
        list[index].isOn = !list[index].isOn
        _switches.value = list
        saveSwitches()
    }

    /**
     * 启动状态轮询（连接成功后自动调用）
     * 立即查询一次，之后每3秒查询
     */
    private fun startStatusPolling() {
        stopStatusPolling()

        // 立即查询一次
        sendStatusQuery()

        // 每3秒轮询
        statusTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    sendStatusQuery()
                }
            }, 3000L, 3000L)
        }
    }


     // ========== 停止状态轮询 ==========

    private fun stopStatusPolling() {
        statusTimer?.cancel()
        statusTimer = null
    }


     // ========== 发送状态查询指令到设备 ==========

    private fun sendStatusQuery() {
        cm.sendRawCommand("STATUS")
    }

    /**
     * 解析 "STATUS:1:1,2:0,3:0,4:0" 并更新开关列表
     * 通道号 1~N 对应 switches 列表的 index 0~N-1
     */
    private fun parseAndApplyStatus(response: String) {
        try {
            val data = response.removePrefix("STATUS:")
            val pairs = data.split(",")

            val list = _switches.value?.toMutableList() ?: return
            var changed = false

            for (pair in pairs) {
                val parts = pair.trim().split(":")
                if (parts.size == 2) {
                    val channel = parts[0].trim().toInt()   // 1, 2, 3, 4
                    val isOn = parts[1].trim() == "1"
                    val index = channel - 1                  // 转为 0, 1, 2, 3

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

    // ========== 规则同步引擎 ==========

    fun syncAllPendingRules() {
        val list = _rules.value ?: return
        for (i in list.indices) {
            val rule = list[i]
            if (rule.syncStatus == 0 || rule.syncStatus == 2 || rule.syncStatus == 4) {
                attemptRuleSync(i)
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

    private fun cancelPendingCallbacks(ruleId: Long) {
        pendingTimeouts.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
        retryRunnables.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
    }

    // ========== 传感器告警检查 ==========

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
    }

    private fun canAlert(key: String, now: Long): Boolean {
        val last = lastAlertTime[key] ?: 0L
        return (now - last) > ALERT_COOLDOWN
    }

    private fun markAlerted(key: String, now: Long) {
        lastAlertTime[key] = now
    }

    // ========== 条件任务检查 ==========

    private fun checkRules(data: SensorData) {
        val sensorMap = mapOf(
            "temp" to data.temp.toDouble(),
            "humi" to data.humi.toDouble(),
            "light" to data.light.toDouble(),
            "pir" to data.pir.toDouble()
        )
        val rulesList = _rules.value ?: return
        val now = System.currentTimeMillis()

        for (rule in rulesList) {
            if (!rule.enabled) continue
            val sensorVal = sensorMap[rule.sensorType] ?: continue
            val threshold = rule.threshold.toDouble()
            val triggered = when (rule.operator) {
                ">" -> sensorVal > threshold
                "<" -> sensorVal < threshold
                "==" -> sensorVal == threshold
                ">=" -> sensorVal >= threshold
                "<=" -> sensorVal <= threshold
                else -> false
            }
            if (triggered && canAlert("rule_${rule.id}", now)) {
                markAlerted("rule_${rule.id}", now)
                cm.sendCommand(rule.actionDevice, rule.actionCmd)
                when (rule.actionDevice) {
                    "light" -> _lightState.value = (rule.actionCmd == "on")
                    "fan" -> _fanState.value = (rule.actionCmd == "on")
                }
                val devName = if (rule.actionDevice == "light") "灯光" else "风扇"
                val cmdName = if (rule.actionCmd == "on") "开启" else "关闭"
                notifHelper.sendRuleTriggered(rule.name, "当${rule.sensorType}${rule.operator}${rule.threshold}时，$cmdName$devName")
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

    fun addRule(rule: Rule) {
        val list = _rules.value ?: mutableListOf()
        list.add(rule)
        _rules.value = list
        saveRules()
        attemptRuleSync(list.size - 1)
    }

    fun removeRule(index: Int) {
        val list = _rules.value ?: return
        if (index in list.indices) {
            cancelPendingCallbacks(list[index].id)
            list.removeAt(index)
            _rules.value = list
            saveRules()
        }
    }

    fun toggleRule(index: Int) {
        val list = _rules.value ?: return
        if (index in list.indices) {
            list[index].enabled = !list[index].enabled
            _rules.value = list
            saveRules()
            list[index].syncStatus = 0
            attemptRuleSync(index)
        }
    }

    fun manualRetryRule(index: Int) {
        val list = _rules.value ?: return
        if (index in list.indices) {
            list[index].retryCount = 0
            list[index].syncStatus = 0
            _rules.value = list
            saveRules()
            attemptRuleSync(index)
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
        if (index in list.indices) {
            val sw = list[index]
            val newIsOn = !sw.isOn
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            cm.sendRawCommand(cmd)
            sw.isOn = newIsOn
            _switches.value = list
            saveSwitches()
        }
    }

    fun toggleSwitchSilent(index: Int) {
        val list = _switches.value ?: return
        if (index in list.indices) {
            val sw = list[index]
            val newIsOn = !sw.isOn
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            cm.sendRawCommand(cmd)
            sw.isOn = newIsOn
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
            "notif_rule_enabled" to prefs.getBoolean("notif_rule_enabled", true)
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
}
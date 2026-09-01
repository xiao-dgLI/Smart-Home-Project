package com.smarthome.app.cloud

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smarthome.app.model.NotificationSetting
import com.smarthome.app.model.SensorData
import com.smarthome.app.notification.NotificationHelper

/**
 * 传感器告警管理器 — 从 MainViewModel 提取的告警检查逻辑
 * 支持动态通知设置（云平台传感器）
 */
class SensorAlertManager(context: Context) {

    private val prefs = context.getSharedPreferences("smart_home_prefs", Context.MODE_PRIVATE)
    private val notifHelper = NotificationHelper(context)
    private val lastAlertTime = mutableMapOf<String, Long>()
    private val cooldown = 60_000L
    private val gson = Gson()

    fun checkAlerts(data: SensorData) {
        if (!prefs.getBoolean("notif_enabled", true)) return
        val now = System.currentTimeMillis()

        checkTemp(data, now)
        checkHumi(data, now)
        checkLight(data, now)
        checkPir(data, now)
        checkFlame(data, now)
        checkGas(data, now)
    }

    /**
     * 检查云平台传感器阈值告警
     * @param sensorValues 传感器实时值，key格式为 "deviceId:apiTag"
     */
    fun checkCloudSensorAlerts(sensorValues: Map<String, String>) {
        if (!prefs.getBoolean("notif_enabled", true)) return

        val settingsJson = prefs.getString("notification_settings", null) ?: return
        if (settingsJson.isEmpty()) return

        val type = object : TypeToken<MutableList<NotificationSetting>>() {}.type
        val settings: MutableList<NotificationSetting> = try {
            gson.fromJson(settingsJson, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }

        val now = System.currentTimeMillis()

        for (setting in settings) {
            if (!setting.enabled) continue

            val key = "${setting.deviceId}:${setting.apiTag}"
            val valueStr = sensorValues[key] ?: continue
            val value = valueStr.toFloatOrNull() ?: continue

            val alertKey = "cloud_${setting.deviceId}_${setting.apiTag}"

            // 检查最高阈值
            if (setting.highThreshold != null && !setting.highThreshold.isNaN()) {
                if (value > setting.highThreshold && canAlert("${alertKey}_high", now)) {
                    notifHelper.sendCloudSensorAlert(
                        setting.sensorName,
                        setting.deviceName,
                        value,
                        setting.highThreshold,
                        setting.unit,
                        true
                    )
                    markAlerted("${alertKey}_high", now)
                }
            }

            // 检查最低阈值
            if (setting.lowThreshold != null && !setting.lowThreshold.isNaN()) {
                if (value < setting.lowThreshold && canAlert("${alertKey}_low", now)) {
                    notifHelper.sendCloudSensorAlert(
                        setting.sensorName,
                        setting.deviceName,
                        value,
                        setting.lowThreshold,
                        setting.unit,
                        false
                    )
                    markAlerted("${alertKey}_low", now)
                }
            }
        }
    }

    fun sendRuleNotification(name: String, description: String) {
        notifHelper.sendRuleTriggered(name, description)
    }

    fun testNotification() {
        notifHelper.sendNotification("🔔 通知测试", "智能家居通知功能正常工作！", 9999)
    }

    private fun checkTemp(data: SensorData, now: Long) {
        if (!prefs.getBoolean("notif_temp_enabled", false)) return
        val high = prefs.getFloat("notif_temp_high", -999f)
        val low = prefs.getFloat("notif_temp_low", -999f)
        if (!high.isNaN() && high != -999f && data.temp > high && canAlert("temp_high", now)) {
            notifHelper.sendTempAlert("温度", data.temp, high, true)
            markAlerted("temp_high", now)
        }
        if (!low.isNaN() && low != -999f && data.temp < low && canAlert("temp_low", now)) {
            notifHelper.sendTempAlert("温度", data.temp, low, false)
            markAlerted("temp_low", now)
        }
    }

    private fun checkHumi(data: SensorData, now: Long) {
        if (!prefs.getBoolean("notif_humi_enabled", false)) return
        val high = prefs.getFloat("notif_humi_high", -999f)
        val low = prefs.getFloat("notif_humi_low", -999f)
        if (!high.isNaN() && high != -999f && data.humi > high && canAlert("humi_high", now)) {
            notifHelper.sendHumiAlert("湿度", data.humi, high, true)
            markAlerted("humi_high", now)
        }
        if (!low.isNaN() && low != -999f && data.humi < low && canAlert("humi_low", now)) {
            notifHelper.sendHumiAlert("湿度", data.humi, low, false)
            markAlerted("humi_low", now)
        }
    }

    private fun checkLight(data: SensorData, now: Long) {
        if (!prefs.getBoolean("notif_light_enabled", false)) return
        val high = prefs.getInt("notif_light_high", -999)
        val low = prefs.getInt("notif_light_low", -999)
        if (high != -999 && data.light > high && canAlert("light_high", now)) {
            notifHelper.sendLightAlert("光照", data.light, high, true)
            markAlerted("light_high", now)
        }
        if (low != -999 && data.light < low && canAlert("light_low", now)) {
            notifHelper.sendLightAlert("光照", data.light, low, false)
            markAlerted("light_low", now)
        }
    }

    private fun checkPir(data: SensorData, now: Long) {
        if (!prefs.getBoolean("notif_pir_enabled", false)) return
        val lastPir = prefs.getInt("last_pir_state", -1)
        if (data.pir != lastPir && canAlert("pir", now)) {
            notifHelper.sendPirAlert("人体", data.pir == 1)
            markAlerted("pir", now)
            prefs.edit().putInt("last_pir_state", data.pir).apply()
        }
    }

    private fun checkFlame(data: SensorData, now: Long) {
        if (!prefs.getBoolean("notif_flame_enabled", false)) return
        if (data.flame == 1 && canAlert("flame", now)) {
            notifHelper.sendFlameAlert("火焰", true)
            markAlerted("flame", now)
            prefs.edit().putInt("last_flame_state", data.flame).apply()
        }
    }

    private fun checkGas(data: SensorData, now: Long) {
        if (!prefs.getBoolean("notif_gas_enabled", false)) return
        val gasThreshold = prefs.getInt("notif_gas_threshold", 400)
        val lastGasState = prefs.getInt("last_gas_alert_state", -1)
        val currentGasState = if (data.gas >= gasThreshold) 1 else 0
        if (currentGasState != lastGasState && canAlert("gas", now)) {
            notifHelper.sendGasAlert("可燃气", data.gas, gasThreshold, currentGasState == 1)
            markAlerted("gas", now)
            prefs.edit().putInt("last_gas_alert_state", currentGasState).apply()
        }
    }

    private fun canAlert(key: String, now: Long): Boolean {
        val last = lastAlertTime[key] ?: 0L
        return (now - last) > cooldown
    }

    private fun markAlerted(key: String, now: Long) {
        lastAlertTime[key] = now
    }

    // 通知设置读写供 Fragment 使用
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

    fun getNotifSettings(): Map<String, Any> = mapOf(
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

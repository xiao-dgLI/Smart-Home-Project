package com.smarthome.app.repository

import android.content.Context
import com.smarthome.app.Constants
import com.smarthome.app.model.SensorData
import com.smarthome.app.notification.NotificationHelper

class NotificationRepository(
    private val context: Context,
    private val notificationHelper: NotificationHelper
) {

    private val dataStore by lazy { DataStoreRepository(context) }
    private val prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
    private val lastAlertTime = mutableMapOf<String, Long>()

    // sensorType → 设备卡片名称（由 MainViewModel 推送）
    private val sensorDeviceNames = mutableMapOf<String, String>()

    fun setSensorDeviceNames(names: Map<String, String>) {
        sensorDeviceNames.clear()
        sensorDeviceNames.putAll(names)
    }

    private fun deviceName(sensorType: String): String =
        sensorDeviceNames[sensorType] ?: when (sensorType) {
            "temp" -> "温度"
            "humi" -> "湿度"
            "light" -> "光照"
            "pir" -> "人体"
            "flame" -> "火焰"
            "gas" -> "可燃气"
            else -> sensorType
        }

    fun checkAlerts(data: SensorData) {
        if (dataStore.getNotifSettings()["notif_enabled"] != true) return
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)

        checkTemp(data, now, prefs)
        checkHumi(data, now, prefs)
        checkLight(data, now, prefs)
        checkPir(data, now, prefs)
        checkFlame(data, now, prefs)
        checkGas(data, now, prefs)
    }

    fun checkCloudSensorAlerts(sensorValues: Map<String, String>) {
        if (dataStore.getNotifSettings()["notif_enabled"] != true) return

        val settings = dataStore.loadNotificationSettings()
        val now = System.currentTimeMillis()

        for (setting in settings) {
            if (!setting.enabled) continue
            val key = "${setting.deviceId}:${setting.apiTag}"
            val valueStr = sensorValues[key] ?: continue
            val value = valueStr.toFloatOrNull() ?: continue
            val alertKey = "cloud_${setting.deviceId}_${setting.apiTag}"

            if (setting.highThreshold != null && !setting.highThreshold.isNaN()) {
                if (value > setting.highThreshold && canAlert("${alertKey}_high", now)) {
                    notificationHelper.sendCloudSensorAlert(
                        setting.sensorName, setting.deviceName, value,
                        setting.highThreshold, setting.unit, true
                    )
                    lastAlertTime["${alertKey}_high"] = now
                }
            }

            if (setting.lowThreshold != null && !setting.lowThreshold.isNaN()) {
                if (value < setting.lowThreshold && canAlert("${alertKey}_low", now)) {
                    notificationHelper.sendCloudSensorAlert(
                        setting.sensorName, setting.deviceName, value,
                        setting.lowThreshold, setting.unit, false
                    )
                    lastAlertTime["${alertKey}_low"] = now
                }
            }
        }
    }

    fun sendRuleNotification(name: String, description: String) {
        val enabled = prefs.getBoolean("notif_rule_enabled", true)
        android.util.Log.d("NotifRepo", "sendRuleNotification: enabled=$enabled name=$name")
        if (!enabled) return
        notificationHelper.sendRuleTriggered(name, description)
    }

    fun testNotification() {
        notificationHelper.sendNotification("\uD83D\uDD14 通知测试", "智能家居通知功能正常工作！", 9999)
    }

    private fun checkTemp(data: SensorData, now: Long, prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("notif_temp_enabled", false)) return
        val high = prefs.getFloat("notif_temp_high", -999f)
        val low = prefs.getFloat("notif_temp_low", -999f)
        if (!high.isNaN() && high != -999f && data.temp > high && canAlert("temp_high", now)) {
            notificationHelper.sendTempAlert(deviceName("temp"), data.temp, high, true)
            lastAlertTime["temp_high"] = now
        }
        if (!low.isNaN() && low != -999f && data.temp < low && canAlert("temp_low", now)) {
            notificationHelper.sendTempAlert(deviceName("temp"), data.temp, low, false)
            lastAlertTime["temp_low"] = now
        }
    }

    private fun checkHumi(data: SensorData, now: Long, prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("notif_humi_enabled", false)) return
        val high = prefs.getFloat("notif_humi_high", -999f)
        val low = prefs.getFloat("notif_humi_low", -999f)
        if (!high.isNaN() && high != -999f && data.humi > high && canAlert("humi_high", now)) {
            notificationHelper.sendHumiAlert(deviceName("humi"), data.humi, high, true)
            lastAlertTime["humi_high"] = now
        }
        if (!low.isNaN() && low != -999f && data.humi < low && canAlert("humi_low", now)) {
            notificationHelper.sendHumiAlert(deviceName("humi"), data.humi, low, false)
            lastAlertTime["humi_low"] = now
        }
    }

    private fun checkLight(data: SensorData, now: Long, prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("notif_light_enabled", false)) return
        val high = prefs.getInt("notif_light_high", -999)
        val low = prefs.getInt("notif_light_low", -999)
        if (high != -999 && data.light > high && canAlert("light_high", now)) {
            notificationHelper.sendLightAlert(deviceName("light"), data.light, high, true)
            lastAlertTime["light_high"] = now
        }
        if (low != -999 && data.light < low && canAlert("light_low", now)) {
            notificationHelper.sendLightAlert(deviceName("light"), data.light, low, false)
            lastAlertTime["light_low"] = now
        }
    }

    private fun checkPir(data: SensorData, now: Long, prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("notif_pir_enabled", false)) return
        val lastPir = prefs.getInt("last_pir_state", -1)
        if (data.pir != lastPir && canAlert("pir", now)) {
            notificationHelper.sendPirAlert(deviceName("pir"), data.pir == 1)
            lastAlertTime["pir"] = now
            prefs.edit().putInt("last_pir_state", data.pir).apply()
        }
    }

    private fun checkFlame(data: SensorData, now: Long, prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("notif_flame_enabled", false)) return
        if (data.flame == 1 && canAlert("flame", now)) {
            notificationHelper.sendFlameAlert(deviceName("flame"), true)
            lastAlertTime["flame"] = now
            prefs.edit().putInt("last_flame_state", data.flame).apply()
        }
    }

    private fun checkGas(data: SensorData, now: Long, prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("notif_gas_enabled", false)) return
        val gasThreshold = prefs.getInt("notif_gas_threshold", 400)
        val lastGasState = prefs.getInt("last_gas_alert_state", -1)
        val currentGasState = if (data.gas >= gasThreshold) 1 else 0
        if (currentGasState != lastGasState && canAlert("gas", now)) {
            notificationHelper.sendGasAlert(deviceName("gas"), data.gas, gasThreshold, currentGasState == 1)
            lastAlertTime["gas"] = now
            prefs.edit().putInt("last_gas_alert_state", currentGasState).apply()
        }
    }

    private fun canAlert(key: String, now: Long): Boolean {
        val last = lastAlertTime[key] ?: 0L
        return (now - last) > Constants.ALERT_COOLDOWN_MS
    }
}

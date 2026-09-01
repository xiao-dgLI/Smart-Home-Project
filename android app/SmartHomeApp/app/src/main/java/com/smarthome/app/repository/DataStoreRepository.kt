package com.smarthome.app.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.smarthome.app.Constants
import com.smarthome.app.model.ControlSwitch
import com.smarthome.app.model.NotificationSetting
import com.smarthome.app.model.Rule
import com.smarthome.app.model.SensorDeviceItem

class DataStoreRepository(context: Context) {

    private val prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    // ========== Rules ==========

    fun saveRules(rules: List<Rule>) {
        prefs.edit().putString("rules", gson.toJson(rules)).apply()
    }

    fun loadRules(): MutableList<Rule> {
        val json = prefs.getString("rules", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Rule>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    // ========== Switches ==========

    fun saveSwitches(switches: List<ControlSwitch>) {
        prefs.edit().putString("switches", gson.toJson(switches)).apply()
    }

    fun loadSwitches(): MutableList<ControlSwitch> {
        val json = prefs.getString("switches", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ControlSwitch>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    // ========== Sensor Cache ==========

    fun isSensorCacheCleared(): Boolean = prefs.getBoolean("sensor_cache_cleared", false)

    fun clearSensorCache() {
        prefs.edit()
            .remove("sensor_devices")
            .putBoolean("sensor_cache_cleared", true)
            .apply()
    }

    // ========== Sensor Devices ==========

    fun saveSensorDevices(devices: List<SensorDeviceItem>) {
        prefs.edit().putString("sensor_devices", gson.toJson(devices)).apply()
    }

    fun loadSensorDevices(): MutableList<SensorDeviceItem> {
        val json = prefs.getString("sensor_devices", null)
        if (json != null) {
            return try {
                val type = object : TypeToken<MutableList<SensorDeviceItem>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (_: Exception) {
                getDefaultSensorDevices().toMutableList()
            }
        }
        return getDefaultSensorDevices().toMutableList()
    }

    private fun getDefaultSensorDevices(): List<SensorDeviceItem> = listOf(
        SensorDeviceItem(icon = "\uD83C\uDF21", name = "温度", sensorType = "temp", unit = "\u00B0C"),
        SensorDeviceItem(icon = "\uD83D\uDCA7", name = "湿度", sensorType = "humi", unit = "%RH"),
        SensorDeviceItem(icon = "☀", name = "光照强度", sensorType = "light", unit = "lux"),
        SensorDeviceItem(icon = "○", name = "人体检测", sensorType = "pir", unit = ""),
        SensorDeviceItem(icon = "\uD83D\uDCA8", name = "可燃气浓度", sensorType = "gas", unit = ""),
        SensorDeviceItem(icon = "\uD83D\uDD25", name = "火焰检测", sensorType = "flame", unit = "")
    )

    // ========== Notification Settings ==========

    fun saveNotificationSettings(settings: List<NotificationSetting>) {
        prefs.edit().putString("notification_settings", gson.toJson(settings)).apply()
    }

    fun loadNotificationSettings(): MutableList<NotificationSetting> {
        val json = prefs.getString("notification_settings", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<NotificationSetting>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    // ========== Project Settings ==========

    fun saveProjectSettings(projectId: Int, projectName: String) {
        prefs.edit()
            .putInt("cloud_project_id", projectId)
            .putString("cloud_project_name", projectName)
            .apply()
    }

    fun loadProjectSettings(): Pair<Int, String> {
        val projectId = prefs.getInt("cloud_project_id", -1)
        val projectName = prefs.getString("cloud_project_name", "") ?: ""
        return Pair(projectId, projectName)
    }

    // ========== Notification Settings (Legacy) ==========

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
        "notif_gas_threshold" to prefs.getInt("notif_gas_threshold", 400),
        "notification_settings" to (prefs.getString("notification_settings", null) ?: "")
    )

    // ========== Connection Mode ==========

    fun saveConnectionMode(mode: String, host: String = "", port: Int = 0) {
        prefs.edit()
            .putString("conn_mode", mode)
            .putString("wifi_host", host)
            .putInt("wifi_port", port)
            .apply()
    }
}

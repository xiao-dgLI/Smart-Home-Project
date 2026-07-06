package com.smarthome.app.model

data class SensorDeviceItem(
    val id: Long = System.currentTimeMillis(),
    val icon: String = "",
    val name: String = "",
    val sensorType: String = "",
    val unit: String = "",
    val source: String? = null,
    val cloudDeviceId: Int? = null,
    val cloudApiTag: String? = null,
    val cloudDeviceName: String? = null,
    val matchedLocalType: String? = null
) {
    val isCloud: Boolean get() = source == "cloud"
    val hasLocalStyle: Boolean get() = matchedLocalType != null

    companion object {
        fun getAllTypes(): List<SensorDeviceItem> = listOf(
            SensorDeviceItem(icon = "🌡", name = "温度",       sensorType = "temp",  unit = "°C"),
            SensorDeviceItem(icon = "💧", name = "湿度",       sensorType = "humi",  unit = "%RH"),
            SensorDeviceItem(icon = "☀",  name = "光照强度",   sensorType = "light", unit = "lux"),
            SensorDeviceItem(icon = "○",  name = "人体检测",   sensorType = "pir",   unit = ""),
            SensorDeviceItem(icon = "💨", name = "可燃气浓度", sensorType = "gas",   unit = ""),
            SensorDeviceItem(icon = "🔥", name = "火焰检测",   sensorType = "flame", unit = "")
        )
    }
}
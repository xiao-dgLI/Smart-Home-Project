package com.smarthome.app.model

data class NotificationSetting(
    val id: Long = System.currentTimeMillis(),
    val deviceId: Int = 0,           // 云平台设备ID
    val deviceName: String = "",     // 设备名称
    val apiTag: String = "",         // 传感器标识
    val sensorName: String = "",     // 传感器名称
    val unit: String = "",           // 单位
    val icon: String = "📡",         // 图标
    val highThreshold: Float? = null, // 最高阈值，null表示不启用
    val lowThreshold: Float? = null,  // 最低阈值，null表示不启用
    val enabled: Boolean = true,     // 是否启用
    val source: String = "cloud"     // 来源：cloud=云平台，local=本地
)

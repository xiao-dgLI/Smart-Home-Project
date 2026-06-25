package com.smarthome.app.model

data class Rule(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var sensorType: String = "temp",
    var operator: String = ">",
    var threshold: Float = 30f,
    var actionNode: String = "0x0004",
    var actionDevice: String = "light",
    var actionCmd: String = "on",
    var enabled: Boolean = true,
    // 同步状态: 0=待同步(红点), 1=已同步(绿点), 2=失败待重试(红点), 3=已过期(灰色卡片), 4=上传中(黄点)
    var syncStatus: Int = 0,
    var retryCount: Int = 0,
    var lastRetryTime: Long = 0L
)
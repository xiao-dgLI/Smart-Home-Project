package com.smarthome.app.model

data class SensorData(
    val node: String = "0x0000",
    var temp: Float = 0f,
    var humi: Float = 0f,
    var light: Int = 0,
    var pir: Int = 0,
    val timestamp: String = ""
)
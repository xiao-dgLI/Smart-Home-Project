package com.smarthome.app.model

data class SensorData(
    val node: String = "",
    val temp: Float = 0f,
    val humi: Float = 0f,
    val light: Int = 0,
    val pir: Int = 0,
    val flame: Int = 0,
    val gas: Int = 0,
    val timestamp: String = ""
)
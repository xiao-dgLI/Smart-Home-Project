package com.smarthome.app.cloud

data class SensorPoint(
    val DeviceID: Int = 0,
    val DeviceName: String = "",
    val ApiTag: String = "",
    val Name: String = "",
    var Value: String = "--",
    val Unit: String = "",
    val At: String = "",
    val TransType: Int = 0,
    val OperType: Int = 0,
    val SensorType: String = ""
) {
    val isActuator: Boolean get() = TransType == 1
}
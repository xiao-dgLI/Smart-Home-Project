package com.smarthome.app.model

data class ControlSwitch(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    var icon: String = "💡",
    var nodeAddr: String = "0x0004",
    var onCommand: String = """{"type":"control","node":"0x0004","action":"on"}""",
    var offCommand: String = """{"type":"control","node":"0x0004","action":"off"}""",
    var isOn: Boolean = false,
    var source: String = "local",
    var cloudDeviceId: Int = 0,
    var cloudApiTag: String = "",
    var operType: Int = 1
) {
    val isCloud: Boolean get() = source == "cloud"
}
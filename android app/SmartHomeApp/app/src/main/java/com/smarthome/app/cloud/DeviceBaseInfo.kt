package com.smarthome.app.cloud

data class DeviceBaseInfo(
    val DeviceID: Int = 0,
    val Name: String = "",
    val IsOnline: Boolean = false,
    val ProjectID: Int = 0,
    val SerialNumber: String = "",   // 设备标识（Tag）
    val SecretKey: String = "",      // 传输密钥
    val Protocol: Int = 0            // 通讯协议：1=TCP, 2=MQTT, 3=HTTP
)
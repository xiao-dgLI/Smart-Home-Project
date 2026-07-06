package com.smarthome.app.cloud

data class DeviceBaseInfo(
    val DeviceID: Int = 0,
    val Name: String = "",
    val IsOnline: Boolean = false,
    val ProjectID: Int = 0,
    val SerialNumber: String = "",   // 设备序列号（clientId）
    val SecretKey: String = ""       // 传输密钥（用于计算 password）
)
package com.smarthome.app.repository

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import com.smarthome.app.model.Rule
import com.smarthome.app.model.SensorData
import com.smarthome.app.network.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocalRepository(private val cm: ConnectionManager) {

    val connectionManager: ConnectionManager get() = cm

    // 状态 Flow

    private val _connectionStatus = MutableStateFlow("未连接")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData

    private val _lightState = MutableStateFlow(false)
    val lightState: StateFlow<Boolean> = _lightState

    private val _fanState = MutableStateFlow(false)
    val fanState: StateFlow<Boolean> = _fanState

    private val _controlAck = MutableStateFlow<String?>(null)
    val controlAck: StateFlow<String?> = _controlAck

    private val _ruleAck = MutableStateFlow<String?>(null)
    val ruleAck: StateFlow<String?> = _ruleAck

    private val _errorMsg = MutableStateFlow<String?>(null)
    val errorMsg: StateFlow<String?> = _errorMsg

    private val _logMessage = MutableStateFlow<String?>(null)
    val logMessage: StateFlow<String?> = _logMessage

    // 连接管理

    val isConnected: Boolean get() = cm.isConnected
    val connectionMode: String get() = cm.connectionMode

    fun connectWifi(host: String, port: Int) = cm.connectWifi(host, port)

    fun connectBluetooth(device: BluetoothDevice) = cm.connectBluetooth(device)

    fun disconnect() = cm.disconnect()

    // 发送命令

    fun sendCommand(device: String, action: String) = cm.sendCommand(device, action)

    fun sendRule(rule: Rule) = cm.sendRule(rule)

    fun sendRawCommand(data: String) = cm.sendRawCommand(data)

    // Handler 设置

    fun setHandler(handler: Handler) = cm.setHandler(handler)
}

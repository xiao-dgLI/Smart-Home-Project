package com.smarthome.app.cloud

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 服务事件总线 — CloudPollingService 与 MainViewModel 之间的通信桥梁
 * Service 发布传感器数据/策略状态，ViewModel 订阅并更新 UI
 */
object ServiceEventBus {

    /** 传感器实时数据更新 */
    private val _sensorUpdate = MutableSharedFlow<List<SensorPoint>>(extraBufferCapacity = 1)
    val sensorUpdate: SharedFlow<List<SensorPoint>> = _sensorUpdate.asSharedFlow()

    /** 策略状态同步结果 */
    private val _strategySync = MutableSharedFlow<List<Map<String, Any?>>>(extraBufferCapacity = 1)
    val strategySync: SharedFlow<List<Map<String, Any?>>> = _strategySync.asSharedFlow()

    /** 服务状态变化 */
    private val _serviceStatus = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val serviceStatus: SharedFlow<String> = _serviceStatus.asSharedFlow()

    /** 设备在线状态更新 */
    private val _deviceOnlineStatus = MutableSharedFlow<Map<Int, Boolean>>(extraBufferCapacity = 1)
    val deviceOnlineStatus: SharedFlow<Map<Int, Boolean>> = _deviceOnlineStatus.asSharedFlow()

    /** App 前台/后台状态 */
    private val _appForeground = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val appForeground: SharedFlow<Boolean> = _appForeground.asSharedFlow()

    suspend fun emitSensorUpdate(sensors: List<SensorPoint>) {
        _sensorUpdate.emit(sensors)
    }

    suspend fun emitStrategySync(strategies: List<Map<String, Any?>>) {
        _strategySync.emit(strategies)
    }

    suspend fun emitServiceStatus(status: String) {
        _serviceStatus.emit(status)
    }

    suspend fun emitDeviceOnlineStatus(statusMap: Map<Int, Boolean>) {
        _deviceOnlineStatus.emit(statusMap)
    }

    fun emitAppForeground(isForeground: Boolean) {
        _appForeground.tryEmit(isForeground)
    }
}

package com.smarthome.app.repository

import com.smarthome.app.cloud.CloudDeviceManager
import com.smarthome.app.cloud.DeviceBaseInfo
import com.smarthome.app.cloud.NleCloudManager
import com.smarthome.app.cloud.ProjectInfo
import com.smarthome.app.cloud.SensorPoint

class CloudRepository(
    private val cloudManager: NleCloudManager,
    private val deviceManager: CloudDeviceManager
) {

    // ========== 连接状态 ==========

    fun isConnected(): Boolean = cloudManager.isConnected()
    fun getAccessToken(): String? = cloudManager.getAccessToken()
    fun setAccessToken(token: String) = cloudManager.setAccessToken(token)
    fun disconnect() = cloudManager.disconnect()

    // ========== 凭证管理 ==========

    fun getSavedCredentials(): Pair<String, String> = cloudManager.getSavedCredentials()
    fun saveMqttCredentials(tag: String, securityKey: String) = cloudManager.saveMqttCredentials(tag, securityKey)
    fun getSavedTag(): String = cloudManager.getSavedTag()
    fun getSavedSecurityKey(): String = cloudManager.getSavedSecurityKey()

    // ========== 登录 ==========

    suspend fun login(account: String, password: String): Pair<Boolean, String> =
        deviceManager.login(account, password)

    // ========== 项目管理 ==========

    suspend fun getProjects(): List<ProjectInfo> = deviceManager.getProjects()

    suspend fun createProject(name: String, industry: Int = 2, netWorkKind: Int = 1, remark: String = ""): Int? =
        deviceManager.createProject(name, industry, netWorkKind, remark)

    suspend fun deleteProject(projectIds: List<Int>): Boolean = deviceManager.deleteProject(projectIds)

    suspend fun connectToProject(projectId: Int): Boolean = deviceManager.connectToProject(projectId)

    // ========== 设备管理 ==========

    suspend fun getDevices(projectId: Int): List<DeviceBaseInfo> = deviceManager.getDevices(projectId)

    suspend fun getDeviceDetail(deviceId: Int, callback: (DeviceBaseInfo?) -> Unit) {
        cloudManager.getDeviceDetail(deviceId, callback)
    }

    suspend fun createDevice(projectId: Int, name: String, tag: String, protocol: Int = 2): Pair<Int?, String> =
        deviceManager.createDevice(projectId, name, tag, protocol)

    suspend fun deleteDevice(deviceId: Int): Boolean = deviceManager.deleteDevice(deviceId)

    // ========== 传感器 ==========

    suspend fun getProjectSensors(projectId: Int): List<SensorPoint> =
        deviceManager.getProjectSensors(projectId)

    suspend fun getDeviceSensors(deviceId: Int): List<SensorPoint> =
        deviceManager.getDeviceSensors(deviceId)

    suspend fun getSensors(devIds: String): List<SensorPoint> =
        deviceManager.getSensors(devIds)

    suspend fun fetchDeviceSensorsWithUnit(deviceIds: List<Int>): Map<String, SensorPoint> =
        deviceManager.fetchDeviceSensorsWithUnit(deviceIds)

    fun getSensorsAsync(devIds: String, callback: (List<SensorPoint>) -> Unit) {
        cloudManager.getSensors(devIds, callback)
    }

    suspend fun createSensor(
        deviceId: Int, apiTag: String, name: String, unit: String,
        transType: Int = 0, operType: Int = 0, sensorType: String = "float",
        initValue: String? = null
    ): SensorPoint? = deviceManager.createSensor(deviceId, apiTag, name, unit, transType, operType, sensorType, initValue)

    suspend fun deleteSensor(deviceId: Int, apiTag: String): Boolean =
        deviceManager.deleteSensor(deviceId, apiTag)

    // ========== 控制命令 ==========

    fun sendCommand(deviceId: Int, apiTag: String, value: String, callback: ((Boolean) -> Unit)? = null) {
        cloudManager.sendCommand(deviceId, apiTag, value, callback)
    }

    // ========== 策略管理 ==========

    suspend fun getStrategies(projectId: Int): List<Map<String, Any?>> =
        deviceManager.getStrategies(projectId)

    fun getStrategiesAsync(projectId: Int, callback: (List<Map<String, Any?>>) -> Unit) {
        cloudManager.getStrategies(projectId, callback)
    }

    suspend fun addStrategy(
        deviceId: Int, kind: Int, expression: String,
        variables: List<Map<String, Any>>,
        actions: List<Map<String, Any>>,
        runTimes: List<Map<String, Any>> = emptyList()
    ): Int? = deviceManager.addStrategy(deviceId, kind, expression, variables, actions, runTimes)

    fun addStrategyAsync(
        deviceId: Int, kind: Int, expression: String,
        variables: List<Map<String, Any>>,
        actions: List<Map<String, Any>>,
        runTimes: List<Map<String, Any>> = emptyList(),
        callback: (Int?) -> Unit
    ) {
        cloudManager.addStrategy(deviceId, kind, expression, variables, actions, runTimes, callback)
    }

    suspend fun deleteStrategy(ids: List<Int>): Boolean = deviceManager.deleteStrategy(ids)

    fun deleteStrategyAsync(ids: List<Int>, callback: (Boolean) -> Unit) {
        cloudManager.deleteStrategy(ids, callback)
    }

    suspend fun enableStrategy(id: Int, enable: Boolean): Boolean =
        deviceManager.enableStrategy(id, enable)

    fun enableStrategyAsync(id: Int, enable: Boolean, callback: (Boolean) -> Unit) {
        cloudManager.enableStrategy(id, enable, callback)
    }

    // ========== 设备在线状态 ==========

    fun getDevicesStatus(deviceIds: List<Int>, callback: (Map<Int, Boolean>) -> Unit) {
        cloudManager.getDevicesStatus(deviceIds, callback)
    }

    // ========== NleCloudManager 直接访问 ==========

    val nleCloudManager: NleCloudManager get() = cloudManager

    fun clearNlePrefs() {
        cloudManager.clearPrefs()
    }
}

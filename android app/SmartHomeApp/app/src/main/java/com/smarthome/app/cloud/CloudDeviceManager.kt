package com.smarthome.app.cloud

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


 // 云平台设备管理器 — 将 NleCloudManager 的回调式 API 包装为 suspend 函数
 // 供 MainViewModel 协程调用

class CloudDeviceManager(private val cloud: NleCloudManager) {

    companion object {
        private const val TAG = "CloudDevMgr"
    }

    suspend fun login(account: String, password: String): Pair<Boolean, String> =
        suspendCancellableCoroutine { cont ->
            cloud.login(account, password) { ok, msg -> cont.resume(Pair(ok, msg)) }
        }

    suspend fun getProjects(): List<ProjectInfo> =
        suspendCancellableCoroutine { cont ->
            cloud.getProjects { list -> cont.resume(list) }
        }

    suspend fun createProject(name: String, industry: Int = 2, netWorkKind: Int = 1, remark: String = ""): Int? =
        suspendCancellableCoroutine { cont ->
            cloud.createProject(name, industry, netWorkKind, remark) { id -> cont.resume(id) }
        }

    suspend fun deleteProject(projectIds: List<Int>): Boolean =
        suspendCancellableCoroutine { cont ->
            cloud.deleteProject(projectIds) { ok -> cont.resume(ok) }
        }

    suspend fun createDevice(projectId: Int, name: String, tag: String, protocol: Int = 2): Pair<Int?, String> =
        suspendCancellableCoroutine { cont ->
            cloud.createDevice(projectId, name, tag, protocol) { id, msg -> cont.resume(Pair(id, msg)) }
        }

    suspend fun deleteDevice(deviceId: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            cloud.deleteDevice(deviceId) { ok -> cont.resume(ok) }
        }

    suspend fun connectToProject(projectId: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            cloud.connectToProject(projectId) { ok -> cont.resume(ok) }
        }

    suspend fun getDevices(projectId: Int): List<DeviceBaseInfo> =
        suspendCancellableCoroutine { cont ->
            cloud.getDevicesByProject(projectId) { list -> cont.resume(list) }
        }

    suspend fun getProjectSensors(projectId: Int): List<SensorPoint> =
        suspendCancellableCoroutine { cont ->
            cloud.getProjectSensors(projectId) { list -> cont.resume(list) }
        }

    suspend fun getDeviceSensors(deviceId: Int): List<SensorPoint> =
        suspendCancellableCoroutine { cont ->
            cloud.getDeviceSensors(deviceId) { list -> cont.resume(list) }
        }

    suspend fun getSensors(devIds: String): List<SensorPoint> =
        suspendCancellableCoroutine { cont ->
            cloud.getSensors(devIds) { list -> cont.resume(list) }
        }

    suspend fun sendCommand(deviceId: Int, apiTag: String, value: String): Boolean =
        suspendCancellableCoroutine { cont ->
            cloud.sendCommand(deviceId, apiTag, value) { ok -> cont.resume(ok) }
        }

    suspend fun addStrategy(
        deviceId: Int, kind: Int, expression: String,
        variables: List<Map<String, Any>>,
        actions: List<Map<String, Any>>,
        runTimes: List<Map<String, Any>> = emptyList()
    ): Int? = suspendCancellableCoroutine { cont ->
        cloud.addStrategy(deviceId, kind, expression, variables, actions, runTimes) { id ->
            cont.resume(id)
        }
    }

    suspend fun getStrategies(projectId: Int): List<Map<String, Any?>> =
        suspendCancellableCoroutine { cont ->
            cloud.getStrategies(projectId) { list -> cont.resume(list) }
        }

    suspend fun deleteStrategy(ids: List<Int>): Boolean =
        suspendCancellableCoroutine { cont ->
            cloud.deleteStrategy(ids) { ok -> cont.resume(ok) }
        }

    suspend fun enableStrategy(id: Int, enable: Boolean): Boolean =
        suspendCancellableCoroutine { cont ->
            cloud.enableStrategy(id, enable) { ok -> cont.resume(ok) }
        }

    suspend fun createSensor(
        deviceId: Int,
        apiTag: String,
        name: String,
        unit: String,
        transType: Int = 0,
        operType: Int = 0,
        sensorType: String = "float",
        initValue: String? = null
    ): SensorPoint? = suspendCancellableCoroutine { cont ->
        cloud.createSensor(deviceId, apiTag, name, unit, transType, operType, sensorType, initValue) { sensor ->
            cont.resume(sensor)
        }
    }

    suspend fun deleteSensor(deviceId: Int, apiTag: String): Boolean =
        suspendCancellableCoroutine { cont ->
            cloud.deleteSensor(deviceId, apiTag) { ok -> cont.resume(ok) }
        }

    suspend fun fetchDeviceSensorsWithUnit(
        deviceIds: List<Int>
    ): Map<String, SensorPoint> {
        val result = mutableMapOf<String, SensorPoint>()
        for (devId in deviceIds) {
            val sensors = getDeviceSensors(devId)
            for (s in sensors) {
                result["${s.DeviceID}:${s.ApiTag}"] = s
            }
        }
        return result
    }

    fun isConnected(): Boolean = cloud.isConnected()
    fun getAccessToken(): String? = cloud.getAccessToken()
    fun disconnect() { cloud.disconnect() }
}

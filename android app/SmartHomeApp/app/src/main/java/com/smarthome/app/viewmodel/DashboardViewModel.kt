package com.smarthome.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarthome.app.MainApplication
import com.smarthome.app.MainViewModel
import com.smarthome.app.model.SensorDeviceItem
import com.smarthome.app.repository.CloudRepository
import com.smarthome.app.repository.DataStoreRepository
import kotlinx.coroutines.launch


 // 仪表盘 ViewModel — 传感器卡片管理、云传感器创建/删除

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MainApplication
    private val cloudRepository: CloudRepository = app.cloudRepository
    private val dataStoreRepository: DataStoreRepository = app.dataStoreRepository

    // 传感器卡片

    fun addSensorDevice(mainVm: MainViewModel, item: SensorDeviceItem) {
        val list = (mainVm.sensorDevices.value ?: mutableListOf()).toMutableList()
        list.add(item)
        mainVm.updateSensorDevices(list)
        dataStoreRepository.saveSensorDevices(list)
    }

    fun removeSensorDevice(mainVm: MainViewModel, id: Long) {
        val list = (mainVm.sensorDevices.value ?: return).toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) {
            mainVm.updateSensorDevices(list)
            dataStoreRepository.saveSensorDevices(list)
        }
    }

    // 云平台传感器 CRUD

    fun createCloudSensor(
        mainVm: MainViewModel,
        deviceId: Int, apiTag: String, name: String, unit: String,
        transType: Int = 0, operType: Int = 0, sensorType: String = "float",
        callback: (Boolean, String) -> Unit
    ) {
        if (!cloudRepository.isConnected()) {
            callback(false, "云平台未连接")
            return
        }

        viewModelScope.launch {
            try {
                val sensor = cloudRepository.createSensor(deviceId, apiTag, name, unit, transType, operType, sensorType)
                if (sensor != null) {
                    val matchedLocalType = matchLocalType(name, apiTag)
                    val icon = guessSensorIcon(apiTag, name)
                    val item = SensorDeviceItem(
                        id = deviceId * 10000L + apiTag.hashCode().toLong(),
                        icon = icon,
                        name = name,
                        sensorType = if (matchedLocalType != null) matchedLocalType else "cloud_sensor",
                        unit = unit,
                        source = "cloud",
                        cloudDeviceId = deviceId,
                        cloudApiTag = apiTag,
                        cloudDeviceName = "",
                        matchedLocalType = matchedLocalType
                    )
                    addSensorDevice(mainVm, item)
                    mainVm.addLog("云传感器创建成功: $name ($apiTag)")
                    callback(true, "传感器创建成功")
                } else {
                    mainVm.addLog("云传感器创建失败: $name")
                    callback(false, "传感器创建失败")
                }
            } catch (e: Exception) {
                mainVm.addLog("云传感器创建异常: ${e.message}")
                callback(false, e.message ?: "创建异常")
            }
        }
    }

    fun deleteCloudSensor(deviceId: Int, apiTag: String, callback: (Boolean) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false); return }
        viewModelScope.launch {
            val success = cloudRepository.deleteSensor(deviceId, apiTag)
            callback(success)
        }
    }

    // 辅助方法

    fun getCloudSensorDeviceItems(mainVm: MainViewModel): List<SensorDeviceItem> {
        return mainVm.sensorDevices.value?.filter { it.source == "cloud" } ?: emptyList()
    }

    private fun guessSensorIcon(apiTag: String, name: String): String {
        val tag = apiTag.lowercase()
        val n = name.lowercase()
        return when {
            "temp" in tag || "温度" in n -> "\uD83C\uDF21"
            "humi" in tag || "湿度" in n -> "\uD83D\uDCA7"
            "light" in tag || "光照" in n || "亮度" in n -> "☀"
            "pir" in tag || "人体" in n || "红外" in n -> "○"
            "flame" in tag || "火" in n -> "\uD83D\uDD25"
            "gas" in tag || "燃气" in n || "烟雾" in n -> "\uD83D\uDCA8"
            "wind" in tag || "风速" in n -> "\uD83C\uDF00"
            "co2" in tag || "二氧化碳" in n -> "\uD83E\uDEC1"
            "pm" in tag || "粉尘" in n -> "\uD83C\uDF2B"
            else -> "\uD83D\uDCE1"
        }
    }

    private fun matchLocalType(sensorName: String, apiTag: String): String? {
        val name = sensorName.lowercase()
        val tag = apiTag.lowercase()
        val keywords = mapOf(
            "temp" to listOf("温度", "气温", "体温"),
            "humi" to listOf("湿度", "含湿"),
            "light" to listOf("光照", "亮度", "光强"),
            "pir" to listOf("人体", "红外", "有人"),
            "gas" to listOf("燃气", "烟雾", "气体", "可燃"),
            "flame" to listOf("火焰", "火灾", "明火")
        )
        for ((type, words) in keywords) {
            if (type in tag) return type
            for (w in words) { if (w in name) return type }
        }
        return null
    }
}

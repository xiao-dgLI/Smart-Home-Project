package com.smarthome.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarthome.app.MainApplication
import com.smarthome.app.MainViewModel
import com.smarthome.app.model.ControlSwitch
import com.smarthome.app.repository.CloudRepository
import com.smarthome.app.repository.DataStoreRepository
import kotlinx.coroutines.launch


 // 控制页 ViewModel — 开关管理、执行器控制、云执行器创建/删除

class ControlViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MainApplication
    private val cloudRepository: CloudRepository = app.cloudRepository
    private val dataStoreRepository: DataStoreRepository = app.dataStoreRepository

    // 开关 CRUD

    fun addSwitch(mainVm: MainViewModel, sw: ControlSwitch) {
        val list = (mainVm.switches.value ?: mutableListOf()).toMutableList()
        list.add(sw)
        mainVm.updateSwitches(list)
        dataStoreRepository.saveSwitches(list)
    }

    fun updateSwitch(mainVm: MainViewModel, index: Int, sw: ControlSwitch) {
        val list = (mainVm.switches.value ?: return).toMutableList()
        if (index in list.indices) {
            list[index] = sw
            mainVm.updateSwitches(list)
            dataStoreRepository.saveSwitches(list)
        }
    }

    fun removeSwitch(mainVm: MainViewModel, index: Int) {
        val list = (mainVm.switches.value ?: return).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            mainVm.updateSwitches(list)
            dataStoreRepository.saveSwitches(list)
        }
    }

    // 开关控制

    fun toggleSwitch(mainVm: MainViewModel, index: Int) {
        val list = mainVm.switches.value ?: return
        if (index !in list.indices) return
        val sw = list[index]
        val newIsOn = !sw.isOn

        if (sw.isCloud) {
            val device = mainVm.getSavedDeviceList().firstOrNull { it.DeviceID == sw.cloudDeviceId }
            if (device != null && !device.IsOnline) {
                mainVm.addLog("设备离线，操作失败: ${sw.name}")
                return
            }
            val value = if (newIsOn) "1" else "0"
            cloudRepository.sendCommand(sw.cloudDeviceId, sw.cloudApiTag, value) { success ->
                if (success) {
                    val newList = list.toMutableList()
                    newList[index] = sw.copy(isOn = newIsOn)
                    mainVm.updateSwitches(newList)
                    dataStoreRepository.saveSwitches(newList)
                    mainVm.addLog("云命令成功: ${sw.name} → ${if (newIsOn) "开启" else "关闭"}")
                } else {
                    mainVm.addLog("云命令失败: ${sw.name}")
                }
            }
        } else {
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            mainVm.sendRawCmd(cmd)
            val newList = list.toMutableList()
            newList[index] = sw.copy(isOn = newIsOn)
            mainVm.updateSwitches(newList)
            dataStoreRepository.saveSwitches(newList)
        }
    }


     // @return true 操作已发起，false 设备离线操作被阻止

    fun toggleSwitchSilent(mainVm: MainViewModel, index: Int): Boolean {
        val list = mainVm.switches.value ?: return false
        if (index !in list.indices) return false
        val sw = list[index]
        val newIsOn = !sw.isOn

        if (sw.isCloud) {
            val device = mainVm.getSavedDeviceList().firstOrNull { it.DeviceID == sw.cloudDeviceId }
            if (device != null && !device.IsOnline) {
                mainVm.addLog("设备离线，操作失败: ${sw.name}")
                return false
            }
            val newList = list.toMutableList()
            newList[index] = sw.copy(isOn = newIsOn)
            mainVm.updateSwitches(newList)
            dataStoreRepository.saveSwitches(newList)

            val value = if (newIsOn) "1" else "0"
            cloudRepository.sendCommand(sw.cloudDeviceId, sw.cloudApiTag, value) { success ->
                if (!success) {
                    val currentList = mainVm.switches.value?.toMutableList() ?: return@sendCommand
                    val curIdx = currentList.indexOfFirst { it.id == sw.id }
                    if (curIdx >= 0) {
                        currentList[curIdx] = currentList[curIdx].copy(isOn = !newIsOn)
                        mainVm.updateSwitches(currentList)
                        dataStoreRepository.saveSwitches(currentList)
                    }
                    mainVm.addLog("云命令失败: ${sw.name}，已回滚")
                }
            }
        } else {
            val cmd = if (newIsOn) sw.onCommand else sw.offCommand
            mainVm.sendRawCmd(cmd)
            val newList = list.toMutableList()
            newList[index] = sw.copy(isOn = newIsOn)
            mainVm.updateSwitches(newList)
            dataStoreRepository.saveSwitches(newList)
        }
        return true
    }

    fun correctSwitchState(mainVm: MainViewModel, index: Int) {
        val list = mainVm.switches.value ?: return
        if (index !in list.indices) return
        val sw = list[index]
        val newList = list.toMutableList()
        newList[index] = sw.copy(isOn = !sw.isOn)
        mainVm.updateSwitches(newList)
        dataStoreRepository.saveSwitches(newList)
    }

    // 云平台执行器 CRUD

    fun createCloudActuator(
        mainVm: MainViewModel,
        deviceId: Int, apiTag: String, name: String, operType: Int = 1,
        callback: (Boolean, String) -> Unit
    ) {
        if (!cloudRepository.isConnected()) {
            callback(false, "云平台未连接")
            return
        }

        viewModelScope.launch {
            try {
                val sensor = cloudRepository.createSensor(
                    deviceId = deviceId, apiTag = apiTag, name = name, unit = "",
                    transType = 1, operType = operType, sensorType = "int",
                    initValue = "0"
                )
                if (sensor != null) {
                    val sw = ControlSwitch(
                        name = name,
                        icon = guessActuatorIcon(apiTag, name, operType),
                        nodeAddr = "", onCommand = "", offCommand = "",
                        isOn = false, source = "cloud",
                        cloudDeviceId = deviceId, cloudApiTag = apiTag, operType = operType
                    )
                    addSwitch(mainVm, sw)
                    mainVm.addLog("云执行器创建成功: $name ($apiTag)")
                    callback(true, "执行器创建成功")
                } else {
                    mainVm.addLog("云执行器创建失败: $name")
                    callback(false, "执行器创建失败")
                }
            } catch (e: Exception) {
                mainVm.addLog("云执行器创建异常: ${e.message}")
                callback(false, e.message ?: "创建异常")
            }
        }
    }

    fun deleteCloudActuator(deviceId: Int, apiTag: String, callback: (Boolean) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false); return }
        viewModelScope.launch {
            val success = cloudRepository.deleteSensor(deviceId, apiTag)
            callback(success)
        }
    }

    // 辅助方法

    fun getCloudActuatorSwitches(mainVm: MainViewModel): List<ControlSwitch> {
        return mainVm.switches.value?.filter { it.isCloud } ?: emptyList()
    }

    fun getCloudSensorDeviceItems(mainVm: MainViewModel): List<com.smarthome.app.model.SensorDeviceItem> {
        return mainVm.sensorDevices.value?.filter { it.source == "cloud" } ?: emptyList()
    }

    private fun guessActuatorIcon(apiTag: String, name: String, operType: Int): String {
        val tag = apiTag.lowercase()
        val n = name.lowercase()
        return when {
            "fan" in tag || "风扇" in n || "风机" in n -> "\uD83C\uDF00"
            "light" in tag || "灯" in n || "led" in tag -> "\uD83D\uDCA1"
            "door" in tag || "门" in n -> "\uD83D\uDEAA"
            "motor" in tag || "电机" in n -> "⚡"
            "pump" in tag || "泵" in n -> "\uD83D\uDCA7"
            "valve" in tag || "阀" in n -> "\uD83D\uDD27"
            "relay" in tag || "继电器" in n -> "\uD83D\uDD0C"
            "alarm" in tag || "警报" in n -> "\uD83D\uDD0A"
            operType == 1 -> "\uD83D\uDCA1"
            operType == 3 -> "\uD83D\uDD18"
            operType == 4 -> "\uD83C\uDF9E"
            else -> "⚡"
        }
    }
}

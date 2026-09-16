package com.smarthome.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarthome.app.MainApplication
import com.smarthome.app.MainViewModel
import com.smarthome.app.cloud.CloudPollingService
import com.smarthome.app.cloud.DeviceBaseInfo
import com.smarthome.app.cloud.ProjectInfo
import com.smarthome.app.repository.CloudRepository
import com.smarthome.app.repository.DataStoreRepository
import kotlinx.coroutines.launch


 // 设置页 ViewModel — WiFi/蓝牙/云平台连接、项目管理、设备管理、自动登录

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MainApplication
    private val cloudRepository: CloudRepository = app.cloudRepository
    private val dataStoreRepository: DataStoreRepository = app.dataStoreRepository

    // WiFi/蓝牙连接

    fun connectWifi(mainVm: MainViewModel, host: String, port: Int) {
        dataStoreRepository.saveConnectionMode("wifi", host, port)
        mainVm.connectWifi(host, port)
    }

    fun connectBluetooth(mainVm: MainViewModel, device: android.bluetooth.BluetoothDevice) {
        mainVm.connectBluetooth(device)
    }

    fun disconnect(mainVm: MainViewModel) {
        mainVm.disconnect()
    }

    // 云平台登录

    fun login(account: String, password: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (ok, msg) = cloudRepository.login(account, password)
            callback(ok, msg)
        }
    }

    fun logout(mainVm: MainViewModel) {
        mainVm.logoutCloud()
    }

    // 云平台连接

    fun connectToProject(mainVm: MainViewModel, projectId: Int, projectName: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = cloudRepository.connectToProject(projectId)
            if (ok) {
                dataStoreRepository.saveProjectSettings(projectId, projectName)
            }
            callback(ok)
        }
    }

    fun disconnectCloud(mainVm: MainViewModel) {
        mainVm.disconnectCloudOnly()
    }

    // 项目管理

    suspend fun getProjects(): List<ProjectInfo> = cloudRepository.getProjects()

    fun createProject(mainVm: MainViewModel, name: String, callback: (Boolean, String) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false, "云平台未连接"); return }
        viewModelScope.launch {
            val id = cloudRepository.createProject(name)
            if (id != null) {
                mainVm.addLog("项目创建成功: $name (ID=$id)")
                val projects = cloudRepository.getProjects()
                mainVm.saveProjectList(projects)
                mainVm.updateSavedProject(id, name)
                callback(true, "项目创建成功")
            } else {
                mainVm.addLog("项目创建失败: $name")
                callback(false, "项目创建失败")
            }
        }
    }

    fun deleteProject(mainVm: MainViewModel, projectId: Int, callback: (Boolean) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false); return }
        viewModelScope.launch {
            val ok = cloudRepository.deleteProject(listOf(projectId))
            if (ok) {
                mainVm.addLog("项目已删除: ID=$projectId")
                val projects = cloudRepository.getProjects()
                mainVm.saveProjectList(projects)
                if (mainVm.getSavedProjectId() == projectId) {
                    mainVm.updateSavedProject(-1, "")
                    dataStoreRepository.saveProjectSettings(-1, "")
                }
            } else {
                mainVm.addLog("项目删除失败: ID=$projectId")
            }
            callback(ok)
        }
    }

    // 设备管理

    suspend fun getDevices(projectId: Int): List<DeviceBaseInfo> = cloudRepository.getDevices(projectId)

    fun createDevice(mainVm: MainViewModel, projectId: Int, name: String, tag: String, protocol: Int = 2, callback: (Boolean, String) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false, "云平台未连接"); return }
        viewModelScope.launch {
            val (id, errorMsg) = cloudRepository.createDevice(projectId, name, tag, protocol)
            if (id != null) {
                mainVm.addLog("设备创建成功: $name (ID=$id)")
                callback(true, "设备创建成功")
            } else {
                mainVm.addLog("设备创建失败: $name - $errorMsg")
                callback(false, errorMsg)
            }
        }
    }

    fun deleteDevice(mainVm: MainViewModel, deviceId: Int, callback: (Boolean) -> Unit) {
        if (!cloudRepository.isConnected()) { callback(false); return }
        viewModelScope.launch {
            val ok = cloudRepository.deleteDevice(deviceId)
            if (ok) mainVm.addLog("设备已删除: ID=$deviceId")
            else mainVm.addLog("设备删除失败: ID=$deviceId")
            callback(ok)
        }
    }

    // 凭证管理

    fun getSavedCredentials(): Pair<String, String> = cloudRepository.getSavedCredentials()
    fun saveMqttCredentials(tag: String, key: String) = cloudRepository.saveMqttCredentials(tag, key)
    fun getSavedTag(): String = cloudRepository.getSavedTag()
    fun getSavedSecurityKey(): String = cloudRepository.getSavedSecurityKey()
}

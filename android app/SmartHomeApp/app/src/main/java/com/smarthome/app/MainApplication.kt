package com.smarthome.app

import android.app.Application
import com.smarthome.app.cloud.CloudDeviceManager
import com.smarthome.app.cloud.NleCloudManager
import com.smarthome.app.network.ConnectionManager
import com.smarthome.app.notification.NotificationHelper
import com.smarthome.app.repository.CloudRepository
import com.smarthome.app.repository.DataStoreRepository
import com.smarthome.app.repository.LocalRepository
import com.smarthome.app.repository.NotificationRepository

class MainApplication : Application() {

    val dataStoreRepository by lazy { DataStoreRepository(this) }
    val notificationHelper by lazy { NotificationHelper(this) }
    val notificationRepository by lazy { NotificationRepository(this, notificationHelper) }

    val nleCloudManager by lazy { NleCloudManager(this) }
    val cloudDeviceManager by lazy { CloudDeviceManager(nleCloudManager) }
    val cloudRepository by lazy { CloudRepository(nleCloudManager, cloudDeviceManager) }

    val connectionManager by lazy { ConnectionManager.instance }
    val localRepository by lazy { LocalRepository(connectionManager) }

    companion object {
        lateinit var instance: MainApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}

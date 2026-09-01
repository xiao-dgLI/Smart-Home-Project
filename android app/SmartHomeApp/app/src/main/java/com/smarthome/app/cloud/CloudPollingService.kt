package com.smarthome.app.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 云平台轮询前台服务
 * 在应用前台/后台均持续运行，直到用户主动退出或清理进程
 */
class CloudPollingService : Service() {

    companion object {
        private const val TAG = "CloudPollSvc"
        private const val CHANNEL_ID = "cloud_polling_channel"
        private const val NOTIF_ID = 5001
        private const val ACTION_START = "com.smarthome.START_CLOUD_POLLING"
        private const val ACTION_STOP = "com.smarthome.STOP_CLOUD_POLLING"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_DEVICE_IDS = "device_ids"
        private const val EXTRA_ACCESS_TOKEN = "access_token"

        fun start(context: Context, projectId: Int, deviceIds: List<Int>, accessToken: String?) {
            val intent = Intent(context, CloudPollingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_DEVICE_IDS, deviceIds.toIntArray())
                putExtra(EXTRA_ACCESS_TOKEN, accessToken)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CloudPollingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    /** 服务级协程作用域 — SupervisorJob 保证单个轮询失败不影响其他 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sensorPollTimer: java.util.Timer? = null
    private var strategyPollTimer: java.util.Timer? = null
    private var deviceStatusPollTimer: java.util.Timer? = null

    private lateinit var cloudMgr: NleCloudManager
    private var projectId: Int = -1
    private var deviceIds: List<Int> = emptyList()

    override fun onCreate() {
        super.onCreate()
        cloudMgr = NleCloudManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                projectId = intent.getIntExtra(EXTRA_PROJECT_ID, -1)
                val ids = intent.getIntArrayExtra(EXTRA_DEVICE_IDS)
                deviceIds = ids?.toList() ?: emptyList()
                val token = intent.getStringExtra(EXTRA_ACCESS_TOKEN)

                if (projectId <= 0 || deviceIds.isEmpty()) {
                    Log.w(TAG, "参数无效，停止服务")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // 设置 AccessToken 到 cloudMgr
                if (token != null) {
                    cloudMgr.setAccessToken(token)
                }

                startForeground(NOTIF_ID, buildNotification("正在轮询云平台..."))
                startPolling()
                serviceScope.launch { ServiceEventBus.emitServiceStatus("轮询服务已启动") }
                // 监听 App 前台状态，前台且无传感器时更新通知为最小化文本
                var lastSensorCount = 0
                serviceScope.launch {
                    ServiceEventBus.appForeground.collect { isForeground ->
                        if (isForeground && lastSensorCount == 0) {
                            updateNotification("智能家居")
                        }
                    }
                }
                // 跟踪传感器数量
                serviceScope.launch {
                    ServiceEventBus.sensorUpdate.collect { sensors ->
                        val onlySensors = sensors.filter { s ->
                            val cacheKey = "${s.DeviceID}:${s.ApiTag}"
                            val meta = MainViewModel.sensorMetaCacheStatic[cacheKey]
                            meta == null || !meta.isActuator
                        }
                        lastSensorCount = onlySensors.size
                    }
                }
            }
            ACTION_STOP -> {
                stopPolling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPolling()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ========== 轮询逻辑 ==========

    private fun startPolling() {
        stopPolling()
        Log.d(TAG, "启动轮询: 项目=$projectId 设备=$deviceIds")

        // 使用 Timer 固定周期触发，与旧版行为一致（不等待请求响应）
        sensorPollTimer = java.util.Timer().apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { pollSensors() }
            }, 0L, 2000L)
        }

        strategyPollTimer = java.util.Timer().apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { pollStrategies() }
            }, 0L, 10_000L)
        }

        // 每2秒刷新设备在线状态
        deviceStatusPollTimer = java.util.Timer().apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { pollDeviceStatus() }
            }, 0L, 2000L)
        }
    }

    private fun pollSensors() {
        val devIds = deviceIds.joinToString(",")
        cloudMgr.getSensors(devIds) { sensors ->
            if (sensors.isNotEmpty()) {
                serviceScope.launch {
                    ServiceEventBus.emitSensorUpdate(sensors)
                    // 只取传感器（TransType=0），排除执行器
                    val onlySensors = sensors.filter { s ->
                        val cacheKey = "${s.DeviceID}:${s.ApiTag}"
                        val meta = MainViewModel.sensorMetaCacheStatic[cacheKey]
                        meta == null || !meta.isActuator
                    }
                    val text = if (onlySensors.isEmpty()) {
                        "程序在后台运行中"
                    } else {
                        val display = onlySensors.take(3).joinToString(" | ") { s ->
                            val cacheKey = "${s.DeviceID}:${s.ApiTag}"
                            val meta = MainViewModel.sensorMetaCacheStatic[cacheKey]
                            val name = meta?.Name?.takeIf { it.isNotBlank() } ?: s.ApiTag
                            val value = if (s.Value == "--" || s.Value.isBlank()) "--" else s.Value
                            val unit = meta?.Unit?.takeIf { it.isNotBlank() } ?: ""
                            if (unit.isNotBlank()) "$name: $value $unit" else "$name: $value"
                        }
                        if (onlySensors.size > 3) "$display ..." else display
                    }
                    updateNotification(text)
                }
            }
        }
    }

    private fun pollStrategies() {
        cloudMgr.getStrategies(projectId) { strategies ->
            serviceScope.launch {
                ServiceEventBus.emitStrategySync(strategies)
            }
        }
    }

    private fun pollDeviceStatus() {
        cloudMgr.getDevicesStatus(deviceIds) { statusMap ->
            if (statusMap.isNotEmpty()) {
                serviceScope.launch {
                    ServiceEventBus.emitDeviceOnlineStatus(statusMap)
                }
            }
        }
    }

    private var pollCount = 0

    private fun stopPolling() {
        sensorPollTimer?.cancel()
        sensorPollTimer = null
        strategyPollTimer?.cancel()
        strategyPollTimer = null
        deviceStatusPollTimer?.cancel()
        deviceStatusPollTimer = null
        pollCount = 0
    }

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "云平台轮询",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示传感器实时数据"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("全屋智能家居")
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(buildContentIntent())
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }
}

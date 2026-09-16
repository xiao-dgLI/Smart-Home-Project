package com.smarthome.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smarthome.app.MainActivity
import com.smarthome.app.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "smarthome_alerts"
        const val CHANNEL_NAME = "智能家居告警"
        const val CHANNEL_DESC = "传感器阈值告警和任务执行通知"

        // 通知 ID
        const val NOTIF_TEMP = 1001
        const val NOTIF_HUMI = 1002
        const val NOTIF_LIGHT = 1003
        const val NOTIF_PIR = 1004
        const val NOTIF_RULE = 1005
        const val NOTIF_FLAME = 1006
        const val NOTIF_GAS = 1007
    }

    private var notifId = 2000

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun sendNotification(title: String, message: String, notifId: Int = this.notifId++) {
        // Android 13+ 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.w("NotifHelper", "POST_NOTIFICATIONS 权限未授予")
                return
            }
        }

        // 点击通知打开 APP
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .build()

        android.util.Log.d("NotifHelper", "发送通知 id=$notifId title=$title")
        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    fun sendTempAlert(deviceName: String, temp: Float, threshold: Float, isAbove: Boolean) {
        val direction = if (isAbove) "高于" else "低于"
        sendNotification(
            "🌡 ${deviceName}温度告警",
            "当前温度 ${String.format("%.1f", temp)}°C，已${direction}阈值 ${String.format("%.1f", threshold)}°C",
            NOTIF_TEMP
        )
    }

    fun sendHumiAlert(deviceName: String, humi: Float, threshold: Float, isAbove: Boolean) {
        val direction = if (isAbove) "高于" else "低于"
        sendNotification(
            "💧 ${deviceName}湿度告警",
            "当前湿度 ${String.format("%.1f", humi)}%RH，已${direction}阈值 ${String.format("%.1f", threshold)}%RH",
            NOTIF_HUMI
        )
    }

    fun sendLightAlert(deviceName: String, light: Int, threshold: Int, isAbove: Boolean) {
        val direction = if (isAbove) "高于" else "低于"
        sendNotification(
            "☀ ${deviceName}光照告警",
            "当前光照 ${light} lux，已${direction}阈值 ${threshold} lux",
            NOTIF_LIGHT
        )
    }

    fun sendPirAlert(deviceName: String, detected: Boolean) {
        if (detected) {
            sendNotification("🚶 ${deviceName}人体检测", "检测到有人进入区域", NOTIF_PIR)
        } else {
            sendNotification("🚶 ${deviceName}人体检测", "区域内已无人", NOTIF_PIR)
        }
    }

    fun sendRuleTriggered(ruleName: String, description: String) {
        sendNotification(
            "⚡ 任务执行: $ruleName",
            description,
            NOTIF_RULE
        )
    }

    fun sendFlameAlert(deviceName: String, detected: Boolean) {
        if (detected) {
            sendNotification(
                "🔥 ${deviceName}火焰报警!",
                "检测到火焰！请立即检查设备安全状况，远离危险区域！",
                NOTIF_FLAME
            )
        } else {
            sendNotification(
                "🔥 ${deviceName}火焰解除",
                "火焰信号已消失，确认安全后请检查设备。",
                NOTIF_FLAME
            )
        }
    }

    fun sendGasAlert(deviceName: String, value: Int, threshold: Int, isAbove: Boolean) {
        if (isAbove) {
            sendNotification(
                "💨 ${deviceName}可燃气泄漏报警!",
                "可燃气浓度超标! 当前值: $value，阈值: $threshold\n" +
                        "请立即开窗通风，关闭气源，远离现场！",
                NOTIF_GAS
            )
        } else {
            sendNotification(
                "💨 ${deviceName}可燃气浓度恢复",
                "可燃气浓度已降至安全范围。当前值: $value，阈值: $threshold",
                NOTIF_GAS
            )
        }
    }


     // 云平台传感器阈值告警
     // @param sensorName 传感器名称
     // @param deviceName 设备名称
     // @param currentValue 当前值
     // @param threshold 阈值
     // @param unit 单位
     // @param isAbove true=高于阈值告警，false=低于阈值告警

    fun sendCloudSensorAlert(
        sensorName: String,
        deviceName: String,
        currentValue: Float,
        threshold: Float,
        unit: String,
        isAbove: Boolean
    ) {
        val direction = if (isAbove) "高于" else "低于"
        val title = "📡 传感器告警: $sensorName"
        val message = "设备: $deviceName\n" +
                "当前值: ${String.format("%.1f", currentValue)} $unit\n" +
                "已${direction}阈值: ${String.format("%.1f", threshold)} $unit"
        sendNotification(title, message)
    }
}

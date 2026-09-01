package com.smarthome.app

object Constants {
    // 网络
    const val NLE_CLOUD_BASE_URL = "https://api.nlecloud.com"
    const val TCP_HOST = "ndp.nlecloud.com"
    const val TCP_PORT = 8600

    // 超时
    const val HTTP_CONNECT_TIMEOUT_SECONDS = 2L
    const val HTTP_READ_TIMEOUT_SECONDS = 2L
    const val HTTP_WRITE_TIMEOUT_SECONDS = 2L
    const val RULE_SYNC_TIMEOUT_MS = 30_000L
    const val RULE_RETRY_DELAY_MS = 60_000L

    // 轮询
    const val SENSOR_POLL_INTERVAL_MS = 2000L
    const val STRATEGY_POLL_INTERVAL_MS = 10_000L
    const val DEVICE_STATUS_POLL_INTERVAL_MS = 2000L
    const val LOCAL_STATUS_POLL_INTERVAL_MS = 3000L

    // 重试
    const val MAX_RETRY_COUNT = 10

    // 告警冷却
    const val ALERT_COOLDOWN_MS = 60_000L

    // 日志
    const val MAX_LOG_COUNT = 100

    // 心跳
    const val HEARTBEAT_INTERVAL_MS = 50_000L
    const val RECONNECT_INTERVAL_MS = 5000L

    // 通知
    const val NOTIF_CHANNEL_ID = "smarthome_alerts"
    const val NOTIF_CHANNEL_NAME = "智能家居告警"
    const val NOTIF_CHANNEL_DESC = "传感器阈值告警和任务执行通知"
    const val CLOUD_POLL_CHANNEL_ID = "cloud_polling_channel"
    const val CLOUD_POLL_NOTIF_ID = 5001

    // SharedPreferences
    const val PREF_NAME = "smart_home_prefs"
    const val NLE_PREF_NAME = "nlecloud_prefs"
}

package com.smarthome.app.model

data class Rule(
    var id: Long = System.currentTimeMillis(),
    var name: String = "",

    // 本地 ZigBee 规则字段
    var sensorType: String = "temp",
    var operator: String = ">",
    var threshold: Float = 30f,
    var actionNode: String = "",
    var actionDevice: String = "light",
    var actionCmd: String = "on",

    // 通用
    var enabled: Boolean = true,
    var syncStatus: Int = 0,
    var retryCount: Int = 0,
    var lastRetryTime: Long = 0L,

    // 云平台策略字段
    var isCloud: Boolean = false,
    var cloudStrategyId: Int = -1,
    var cloudProjectId: Int = -1,

    // 条件侧
    var cloudSensorDeviceId: Int = -1,
    var cloudSensorApiTag: String = "",
    var cloudSensorName: String = "",
    var cloudConditionOperator: Int = 1,

    // 动作侧
    var cloudActuatorDeviceId: Int = -1,
    var cloudActuatorApiTag: String = "",
    var cloudActuatorName: String = "",
    var cloudActionValue: String = "1",

    // 定时任务字段
    var isTimedTask: Boolean = false,
    var runTimePeriod: Int = 0,
    var runTimeDay: Int = 0,
    var runTimeSlots: List<RunTimeSlot> = emptyList()
) {
    /** 兼容旧的单时间字段读取 */
    val runTimeHour: Int get() = runTimeSlots.firstOrNull()?.hour ?: 8
    val runTimeMinute: Int get() = runTimeSlots.firstOrNull()?.minute ?: 0
}

data class RunTimeSlot(
    var hour: Int = 8,
    var minute: Int = 0
)
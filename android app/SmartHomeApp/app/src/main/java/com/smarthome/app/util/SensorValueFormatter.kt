package com.smarthome.app.util

import com.smarthome.app.model.SensorData
import com.smarthome.app.model.SensorDeviceItem

/**
 * 传感器值格式化工具 — 消除 Adapter 中的重复 when 分支
 */
object SensorValueFormatter {

    data class FormattedValue(
        val text: String,
        val textColor: Int,
        val iconText: String? = null,
        val iconColor: Int? = null
    )

    fun formatLocal(item: SensorDeviceItem, data: SensorData?): FormattedValue {
        if (data == null) return FormattedValue("--", DEFAULT_COLOR)

        return when (item.sensorType) {
            "temp" -> formatTemp(data.temp, item.unit)
            "humi" -> formatHumi(data.humi, item.unit)
            "light" -> formatLight(data.light, item.unit)
            "pir" -> formatPir(data.pir == 1)
            "flame" -> formatFlame(data.flame == 1)
            "gas" -> formatGas(data.gas)
            else -> FormattedValue("--", DEFAULT_COLOR)
        }
    }

    fun formatCloud(item: SensorDeviceItem, value: String?): FormattedValue {
        if (value == null || value == "--") return FormattedValue("--", GRAY_COLOR)

        return when (item.matchedLocalType) {
            "temp" -> {
                val numValue = value.toFloatOrNull() ?: 0f
                formatTemp(numValue, item.unit)
            }
            "humi" -> {
                val numValue = value.toFloatOrNull() ?: 0f
                formatHumi(numValue, item.unit)
            }
            "light" -> {
                val numValue = value.toFloatOrNull()?.toInt() ?: 0
                formatLight(numValue, item.unit)
            }
            "pir" -> formatPir(value == "1" || value.equals("true", ignoreCase = true))
            "flame" -> formatFlame(value == "1" || value.equals("true", ignoreCase = true))
            "gas" -> {
                val gasValue = value.toFloatOrNull()?.toInt() ?: 0
                formatGas(gasValue)
            }
            else -> FormattedValue(
                text = if (item.unit.isNotEmpty()) "$value ${item.unit}" else value,
                textColor = GREEN_COLOR
            )
        }
    }

    private fun formatTemp(temp: Float, unit: String): FormattedValue {
        val color = when {
            temp > 35 -> RED_COLOR
            temp > 28 -> ORANGE_COLOR
            else -> GREEN_COLOR
        }
        return FormattedValue(String.format("%.1f %s", temp, unit), color)
    }

    private fun formatHumi(humi: Float, unit: String): FormattedValue {
        return FormattedValue(String.format("%.1f %s", humi, unit), BLUE_COLOR)
    }

    private fun formatLight(light: Int, unit: String): FormattedValue {
        val color = if (light > 500) YELLOW_COLOR else GRAY_DARK_COLOR
        return FormattedValue("$light $unit", color)
    }

    private fun formatPir(detected: Boolean): FormattedValue {
        val color = if (detected) BLACK_COLOR else GRAY_COLOR
        val icon = if (detected) "●" else "○"
        val text = if (detected) "有人" else "无人"
        return FormattedValue(text, color, icon, color)
    }

    private fun formatFlame(detected: Boolean): FormattedValue {
        return if (detected) {
            FormattedValue("\u26A0 检测到火焰", RED_COLOR, null, RED_COLOR)
        } else {
            FormattedValue("安全", GREEN_COLOR, null, BLACK_COLOR)
        }
    }

    private fun formatGas(gas: Int): FormattedValue {
        val text = if (gas >= 400) "$gas \u26A0" else "$gas"
        val color = if (gas >= 400) AMBER_COLOR else GREEN_COLOR
        val iconColor = if (gas >= 400) AMBER_COLOR else BLACK_COLOR
        return FormattedValue(text, color, null, iconColor)
    }

    // 颜色常量
    private const val DEFAULT_COLOR = 0xFF4CAF50.toInt()
    private const val GREEN_COLOR = 0xFF4CAF50.toInt()
    private const val RED_COLOR = 0xFFFF4444.toInt()
    private const val ORANGE_COLOR = 0xFFFF8800.toInt()
    private const val YELLOW_COLOR = 0xFFFFC107.toInt()
    private const val AMBER_COLOR = 0xFFFF6F00.toInt()
    private const val BLUE_COLOR = 0xFF2196F3.toInt()
    private const val GRAY_COLOR = 0xFF9E9E9E.toInt()
    private const val GRAY_DARK_COLOR = 0xFF607D8B.toInt()
    private const val BLACK_COLOR = 0xFF212121.toInt()
}

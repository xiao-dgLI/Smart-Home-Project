package com.smarthome.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.smarthome.app.R
import com.smarthome.app.model.SensorData
import com.smarthome.app.model.SensorDeviceItem

class SensorCardAdapter(
    private val items: MutableList<SensorDeviceItem>,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<SensorCardAdapter.ViewHolder>() {

    private var sensorData: SensorData? = null
    //云端传感器实时值
    private var cloudValues: Map<String, String> = emptyMap()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.cardSensor)
        val tvIcon: TextView = view.findViewById(R.id.tvSensorIcon)
        val tvName: TextView = view.findViewById(R.id.tvSensorName)
        val tvValue: TextView = view.findViewById(R.id.tvSensorValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sensor_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvIcon.text = item.icon
        holder.tvName.text = item.name

        if (item.isCloud) {
            if (item.hasLocalStyle) {
                // 云端传感器但匹配本地类型，使用本地样式显示
                bindCloudSensorWithLocalStyle(holder, item)
            } else {
                bindCloudSensor(holder, item)
            }
        } else {
            bindLocalSensor(holder, item)
        }

        holder.card.setOnLongClickListener {
            onLongClick(holder.adapterPosition)
            true
        }
    }

    // 云端传感器 - 使用本地样式（颜色编码等）
    private fun bindCloudSensorWithLocalStyle(holder: ViewHolder, item: SensorDeviceItem) {
        val key = "${item.cloudDeviceId}:${item.cloudApiTag}"
        val value = cloudValues[key]

        if (value != null && value != "--") {
            val numValue = value.toFloatOrNull()

            when (item.matchedLocalType) {
                "temp" -> {
                    holder.tvValue.text = if (item.unit.isNotEmpty()) "$value ${item.unit}" else value
                    val temp = numValue ?: 0f
                    holder.tvValue.setTextColor(when {
                        temp > 35 -> 0xFFFF4444.toInt()
                        temp > 28 -> 0xFFFF8800.toInt()
                        else -> 0xFF4CAF50.toInt()
                    })
                }
                "humi" -> {
                    holder.tvValue.text = if (item.unit.isNotEmpty()) "$value ${item.unit}" else value
                    holder.tvValue.setTextColor(0xFF2196F3.toInt())
                }
                "light" -> {
                    holder.tvValue.text = if (item.unit.isNotEmpty()) "$value ${item.unit}" else value
                    val light = numValue?.toInt() ?: 0
                    holder.tvValue.setTextColor(
                        if (light > 500) 0xFFFFC107.toInt() else 0xFF607D8B.toInt()
                    )
                }
                "pir" -> {
                    val isDetected = value == "1" || value.equals("true", ignoreCase = true)
                    holder.tvValue.text = if (isDetected) "有人" else "无人"
                    holder.tvIcon.text = if (isDetected) "●" else "○"
                    val color = if (isDetected) 0xFF212121.toInt() else 0xFF9E9E9E.toInt()
                    holder.tvValue.setTextColor(color)
                    holder.tvIcon.setTextColor(color)
                }
                "flame" -> {
                    val isDetected = value == "1" || value.equals("true", ignoreCase = true)
                    if (isDetected) {
                        holder.tvValue.text = "⚠ 检测到火焰"
                        holder.tvValue.setTextColor(0xFFE53935.toInt())
                        holder.tvIcon.setTextColor(0xFFE53935.toInt())
                    } else {
                        holder.tvValue.text = "安全"
                        holder.tvValue.setTextColor(0xFF4CAF50.toInt())
                        holder.tvIcon.setTextColor(0xFF212121.toInt())
                    }
                }
                "gas" -> {
                    val gasValue = numValue?.toInt() ?: 0
                    holder.tvValue.text = if (gasValue >= 400) "$gasValue ⚠" else "$gasValue"
                    val color = if (gasValue >= 400) 0xFFFF6F00.toInt() else 0xFF4CAF50.toInt()
                    holder.tvValue.setTextColor(color)
                    holder.tvIcon.setTextColor(if (gasValue >= 400) 0xFFFF6F00.toInt() else 0xFF212121.toInt())
                }
                else -> {
                    // 默认使用普通云端样式
                    holder.tvValue.text = if (item.unit.isNotEmpty()) "$value ${item.unit}" else value
                    holder.tvValue.setTextColor(0xFF4CAF50.toInt())
                }
            }
        } else {
            holder.tvValue.text = "--"
            holder.tvValue.setTextColor(0xFF9E9E9E.toInt())
        }
        holder.tvIcon.setTextColor(holder.tvIcon.textColors) // 保持图标颜色
    }

    // 云端传感器绑定
    private fun bindCloudSensor(holder: ViewHolder, item: SensorDeviceItem) {
        val key = "${item.cloudDeviceId}:${item.cloudApiTag}"
        val value = cloudValues[key]

        if (value != null && value != "--") {
            holder.tvValue.text = if (item.unit.isNotEmpty()) "$value ${item.unit}" else value
            holder.tvValue.setTextColor(0xFF4CAF50.toInt())
        } else {
            holder.tvValue.text = "--"
            holder.tvValue.setTextColor(0xFF9E9E9E.toInt())
        }
        holder.tvIcon.setTextColor(0xFF212121.toInt())
    }

    private fun bindLocalSensor(holder: ViewHolder, item: SensorDeviceItem) {
        val data = sensorData
        if (data != null) {
            when (item.sensorType) {
                "temp" -> {
                    holder.tvValue.text = String.format("%.1f %s", data.temp, item.unit)
                    holder.tvValue.setTextColor(when {
                        data.temp > 35 -> 0xFFFF4444.toInt()
                        data.temp > 28 -> 0xFFFF8800.toInt()
                        else           -> 0xFF4CAF50.toInt()
                    })
                }
                "humi" -> {
                    holder.tvValue.text = String.format("%.1f %s", data.humi, item.unit)
                    holder.tvValue.setTextColor(0xFF2196F3.toInt())
                }
                "light" -> {
                    holder.tvValue.text = "${data.light} ${item.unit}"
                    holder.tvValue.setTextColor(
                        if (data.light > 500) 0xFFFFC107.toInt() else 0xFF607D8B.toInt()
                    )
                }
                "pir" -> {
                    holder.tvValue.text = if (data.pir == 1) "有人" else "无人"
                    holder.tvIcon.text = if (data.pir == 1) "●" else "○"
                    val color = if (data.pir == 1) 0xFF212121.toInt() else 0xFF9E9E9E.toInt()
                    holder.tvValue.setTextColor(color)
                    holder.tvIcon.setTextColor(color)
                }
                "flame" -> {
                    if (data.flame == 1) {
                        holder.tvValue.text = "⚠ 检测到火焰"
                        holder.tvValue.setTextColor(0xFFE53935.toInt())
                        holder.tvIcon.setTextColor(0xFFE53935.toInt())
                    } else {
                        holder.tvValue.text = "安全"
                        holder.tvValue.setTextColor(0xFF4CAF50.toInt())
                        holder.tvIcon.setTextColor(0xFF212121.toInt())
                    }
                }
                "gas" -> {
                    holder.tvValue.text =
                        if (data.gas >= 400) "${data.gas} ⚠" else "${data.gas}"
                    val color =
                        if (data.gas >= 400) 0xFFFF6F00.toInt() else 0xFF4CAF50.toInt()
                    holder.tvValue.setTextColor(color)
                    holder.tvIcon.setTextColor(
                        if (data.gas >= 400) 0xFFFF6F00.toInt() else 0xFF212121.toInt()
                    )
                }
            }
        } else {
            holder.tvValue.text = "--"
            holder.tvValue.setTextColor(0xFF4CAF50.toInt())
        }
    }

    override fun getItemCount() = items.size

    fun updateSensorData(data: SensorData) {
        sensorData = data
        notifyDataSetChanged()
    }

    // 更新云端实时数值
    fun updateCloudValues(values: Map<String, String>) {
        cloudValues = values
        notifyDataSetChanged()
    }

    fun getItems(): List<SensorDeviceItem> = items.toList()

    fun setItems(newItems: List<SensorDeviceItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
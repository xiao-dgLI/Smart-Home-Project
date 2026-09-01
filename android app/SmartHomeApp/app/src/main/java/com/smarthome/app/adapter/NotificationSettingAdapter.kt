package com.smarthome.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.smarthome.app.R
import com.smarthome.app.model.NotificationSetting

class NotificationSettingAdapter(
    private val onToggle: (Int, Boolean) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onThresholdChanged: (Int, Float?, Float?) -> Unit
) : RecyclerView.Adapter<NotificationSettingAdapter.ViewHolder>() {

    private var items: MutableList<NotificationSetting> = mutableListOf()

    fun submitList(list: List<NotificationSetting>) {
        items = list.toMutableList()
        notifyDataSetChanged()
    }

    fun getItems(): List<NotificationSetting> = items.toList()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification_setting, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvIcon: TextView = view.findViewById(R.id.tvIcon)
        private val tvSensorName: TextView = view.findViewById(R.id.tvSensorName)
        private val tvDeviceInfo: TextView = view.findViewById(R.id.tvDeviceInfo)
        private val switchEnabled: Switch = view.findViewById(R.id.switchEnabled)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        private val etHighThreshold: EditText = view.findViewById(R.id.etHighThreshold)
        private val etLowThreshold: EditText = view.findViewById(R.id.etLowThreshold)
        private val tvUnit: TextView = view.findViewById(R.id.tvUnit)
        private val tvUnit2: TextView = view.findViewById(R.id.tvUnit2)
        private val tvHint: TextView = view.findViewById(R.id.tvHint)

        fun bind(setting: NotificationSetting, position: Int) {
            tvIcon.text = setting.icon
            tvSensorName.text = setting.sensorName
            tvDeviceInfo.text = "${setting.deviceName} (${setting.apiTag})"
            tvUnit.text = setting.unit
            tvUnit2.text = setting.unit

            switchEnabled.isChecked = setting.enabled

            // 加载阈值
            etHighThreshold.setText(setting.highThreshold?.let { 
                if (it.isNaN()) "" else it.toString() 
            } ?: "")
            etLowThreshold.setText(setting.lowThreshold?.let { 
                if (it.isNaN()) "" else it.toString() 
            } ?: "")

            // 根据是否有阈值显示提示
            updateHint()

            // 开关切换
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(position, isChecked)
            }

            // 删除按钮
            btnDelete.setOnClickListener {
                onDelete(position)
            }

            // 阈值变化监听
            etHighThreshold.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val high = parseThreshold(etHighThreshold)
                    val low = parseThreshold(etLowThreshold)
                    onThresholdChanged(position, high, low)
                    updateHint()
                }
            }

            etLowThreshold.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val high = parseThreshold(etHighThreshold)
                    val low = parseThreshold(etLowThreshold)
                    onThresholdChanged(position, high, low)
                    updateHint()
                }
            }
        }

        private fun parseThreshold(et: EditText): Float? {
            val text = et.text.toString().trim()
            return if (text.isEmpty()) null else text.toFloatOrNull()
        }

        private fun updateHint() {
            val high = etHighThreshold.text.toString().trim()
            val low = etLowThreshold.text.toString().trim()

            when {
                high.isEmpty() && low.isEmpty() -> {
                    tvHint.text = "请至少设置一个阈值"
                    tvHint.setTextColor(0xFFFF9800.toInt())
                }
                high.isEmpty() -> {
                    tvHint.text = "仅设置最低阈值，低于此值时通知"
                    tvHint.setTextColor(0xFF757575.toInt())
                }
                low.isEmpty() -> {
                    tvHint.text = "仅设置最高阈值，高于此值时通知"
                    tvHint.setTextColor(0xFF757575.toInt())
                }
                else -> {
                    tvHint.text = "高于最高或低于最低阈值时通知"
                    tvHint.setTextColor(0xFF757575.toInt())
                }
            }
        }
    }
}

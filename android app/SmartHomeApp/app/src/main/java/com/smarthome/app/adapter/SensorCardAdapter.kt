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
import com.smarthome.app.util.SensorValueFormatter

class SensorCardAdapter(
    private val items: MutableList<SensorDeviceItem>,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<SensorCardAdapter.ViewHolder>() {

    private var sensorData: SensorData? = null
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

        val formatted = if (item.isCloud) {
            val key = "${item.cloudDeviceId}:${item.cloudApiTag}"
            SensorValueFormatter.formatCloud(item, cloudValues[key])
        } else {
            SensorValueFormatter.formatLocal(item, sensorData)
        }

        holder.tvValue.text = formatted.text
        holder.tvValue.setTextColor(formatted.textColor)

        formatted.iconText?.let { holder.tvIcon.text = it }
        formatted.iconColor?.let { holder.tvIcon.setTextColor(it) }

        holder.card.setOnLongClickListener {
            onLongClick(holder.adapterPosition)
            true
        }
    }

    override fun getItemCount() = items.size

    fun updateSensorData(data: SensorData) {
        sensorData = data
        notifyDataSetChanged()
    }

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

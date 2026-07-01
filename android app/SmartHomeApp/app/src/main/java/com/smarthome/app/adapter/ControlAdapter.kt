package com.smarthome.app.adapter

import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.smarthome.app.R
import com.smarthome.app.model.ControlSwitch

class ControlAdapter(
    private val onToggle: (Int) -> Unit,
    private val onEdit: (Int) -> Unit,
    private val onEditOnCmd: (Int) -> Unit,
    private val onEditOffCmd: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onCorrect: (Int) -> Unit
) : RecyclerView.Adapter<ControlAdapter.SwitchViewHolder>() {

    private var items: List<ControlSwitch> = emptyList()

    fun submitList(list: List<ControlSwitch>) {
        items = list
        notifyDataSetChanged()
    }

    fun animateToggle(index: Int, newIsOn: Boolean, recyclerView: RecyclerView?) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(index)
        if (holder is SwitchViewHolder) {
            holder.animateToggle(newIsOn)
        }
    }


    //供轮询时更新单个卡片状态

    fun updateStateFromDevice(index: Int, isOn: Boolean, recyclerView: RecyclerView?) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(index)
        if (holder is SwitchViewHolder) {
            holder.animateToggle(isOn)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SwitchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_control_switch, parent, false)
        return SwitchViewHolder(view)
    }

    override fun onBindViewHolder(holder: SwitchViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    inner class SwitchViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvIcon: TextView = view.findViewById(R.id.tvIcon)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvState: TextView = view.findViewById(R.id.tvState)
        private val btnToggle: MaterialButton = view.findViewById(R.id.btnToggle)
        private val btnMore: ImageButton = view.findViewById(R.id.btnMore)

        fun bind(sw: ControlSwitch, index: Int) {
            tvIcon.text = sw.icon
            tvName.text = sw.name

            tvState.text = if (sw.isOn) "已开启" else "已关闭"
            tvState.setTextColor(if (sw.isOn) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt())

            btnToggle.text = if (sw.isOn) "关闭" else "开启"

            tvIcon.alpha = if (sw.isOn) 1.0f else 0.3f

            btnToggle.setOnClickListener {
                onToggle(index)
            }

            btnMore.setOnClickListener { anchor ->
                val popup = PopupMenu(anchor.context, anchor)
                popup.menu.add(0, 1, 0, "编辑名称")
                popup.menu.add(0, 2, 1, "编辑开启指令")
                popup.menu.add(0, 3, 2, "编辑关闭指令")
                popup.menu.add(0, 4, 3, "纠错")
                popup.menu.add(0, 5, 4, "删除设备")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onEdit(index); true }
                        2 -> { onEditOnCmd(index); true }
                        3 -> { onEditOffCmd(index); true }
                        4 -> { onCorrect(index); true }
                        5 -> { onDelete(index); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        fun animateToggle(newIsOn: Boolean) {
            tvState.text = if (newIsOn) "已开启" else "已关闭"
            tvState.setTextColor(if (newIsOn) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt())
            btnToggle.text = if (newIsOn) "关闭" else "开启"

            val targetAlpha = if (newIsOn) 1.0f else 0.3f
            tvIcon.animate()
                .alpha(targetAlpha)
                .setDuration(300)
                .setListener(null)
                .start()
        }
    }
}
package com.smarthome.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R
import com.smarthome.app.model.Rule

class RuleFragment : Fragment() {

    private lateinit var vm: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_rule, container, false)

        vm = (requireActivity() as MainActivity).viewModel

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerRules)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val adapter = RuleAdapter(
            onToggle = { pos -> vm.toggleRule(pos) },
            onDelete = { pos ->
                val rules = vm.rules.value
                if (rules != null && pos in rules.indices) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("确认删除")
                        .setMessage("确定要删除任务「${rules[pos].name}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            vm.removeRule(pos)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            },
            onRetry = { pos -> vm.manualRetryRule(pos) }
        )
        recyclerView.adapter = adapter

        vm.rules.observe(viewLifecycleOwner) { rules ->
            adapter.submitList(rules.toList())
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddRule).setOnClickListener {
            showAddRuleDialog()
        }

        return view
    }

    private fun showAddRuleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_rule, null)
        val etName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val spSensor = dialogView.findViewById<Spinner>(R.id.spSensorType)
        val spOperator = dialogView.findViewById<Spinner>(R.id.spOperator)
        val etThreshold = dialogView.findViewById<EditText>(R.id.etThreshold)
        val spDevice = dialogView.findViewById<Spinner>(R.id.spActionDevice)
        val spCmd = dialogView.findViewById<Spinner>(R.id.spActionCmd)

        val sensorValues = arrayOf("temp", "humi", "light", "pir")
        val operatorValues = arrayOf(">", "<", "==", ">=", "<=")
        val deviceValues = arrayOf("light", "fan")
        val cmdValues = arrayOf("on", "off")

        spSensor.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("温度 (temp)", "湿度 (humi)", "光照 (light)", "人体 (pir)"))
        spOperator.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("大于 (>)", "小于 (<)", "等于 (==)", "大于等于 (>=)", "小于等于 (<=)"))
        spDevice.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("灯光 (light)", "风扇 (fan)"))
        spCmd.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("开启 (on)", "关闭 (off)"))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加条件任务")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                try {
                    val rule = Rule(
                        name = etName.text.toString().ifBlank { "规则" },
                        sensorType = sensorValues[spSensor.selectedItemPosition],
                        operator = operatorValues[spOperator.selectedItemPosition],
                        threshold = etThreshold.text.toString().toFloatOrNull() ?: 0f,
                        actionNode = if (deviceValues[spDevice.selectedItemPosition] == "light") "0x0004" else "0x0005",
                        actionDevice = deviceValues[spDevice.selectedItemPosition],
                        actionCmd = cmdValues[spCmd.selectedItemPosition],
                        enabled = true
                    )
                    vm.addRule(rule)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

// ==================== Adapter ====================

class RuleAdapter(
    private val onToggle: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onRetry: (Int) -> Unit
) : RecyclerView.Adapter<RuleAdapter.ViewHolder>() {

    private var items: List<Rule> = emptyList()

    fun submitList(list: List<Rule>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: CardView = view.findViewById(R.id.cardRule)
        private val tvName: TextView = view.findViewById(R.id.tvRuleName)
        private val tvDesc: TextView = view.findViewById(R.id.tvRuleDesc)
        private val tvSyncInfo: TextView = view.findViewById(R.id.tvSyncInfo)
        private val viewDot: View = view.findViewById(R.id.viewSyncDot)
        private val switch: Switch = view.findViewById(R.id.switchRule)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteRule)

        fun bind(rule: Rule) {
            tvName.text = rule.name
            val devName = if (rule.actionDevice == "light") "灯光" else "风扇"
            val cmdName = if (rule.actionCmd == "on") "开启" else "关闭"
            tvDesc.text = "当 ${rule.sensorType} ${rule.operator} ${rule.threshold} 时，$cmdName$devName"

            // Switch 状态
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = rule.enabled
            switch.setOnCheckedChangeListener { _, _ ->
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onToggle(pos)
            }

            // 删除
            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(pos)
            }

            // 长按卡片手动重试
            card.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && (rule.syncStatus == 2 || rule.syncStatus == 3)) {
                    onRetry(pos)
                    Toast.makeText(itemView.context, "正在重试同步...", Toast.LENGTH_SHORT).show()
                }
                true
            }

            // ========== 同步状态显示 ==========
            val dotColor: Int
            val syncText: String

            when (rule.syncStatus) {
                0 -> {
                    dotColor = 0xFFFF5252.toInt()   // 红
                    syncText = "待同步"
                    card.alpha = 1.0f
                }
                1 -> {
                    dotColor = 0xFF4CAF50.toInt()   // 绿
                    syncText = "已同步"
                    card.alpha = 1.0f
                }
                2 -> {
                    dotColor = 0xFFFF5252.toInt()   // 红
                    syncText = "同步失败，${rule.retryCount}/10 次，1分钟后重试"
                    card.alpha = 1.0f
                }
                3 -> {
                    dotColor = 0xFFBDBDBD.toInt()   // 灰
                    syncText = "同步失败，已重试10次，长按可重试"
                    card.alpha = 0.4f
                }
                4 -> {
                    dotColor = 0xFFFFEB3B.toInt()   // 黄
                    syncText = "正在上传..."
                    card.alpha = 1.0f
                }
                else -> {
                    dotColor = 0xFF9E9E9E.toInt()
                    syncText = ""
                    card.alpha = 1.0f
                }
            }

            viewDot.background.setTint(dotColor)
            tvSyncInfo.text = syncText
        }
    }
}
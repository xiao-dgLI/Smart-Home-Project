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
import com.smarthome.app.model.RunTimeSlot

class RuleFragment : Fragment() {

    private lateinit var vm: MainViewModel

    // 定时任务对话框临时数据
    private val timeSlots = mutableListOf<RunTimeSlot>()
    private lateinit var slotContainer: LinearLayout

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
                    val rule = rules[pos]
                    val suffix = if (rule.isCloud) "（将同时删除云平台策略）" else ""
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("确认删除")
                        .setMessage("确定要删除任务「${rule.name}」$suffix 吗？")
                        .setPositiveButton("删除") { _, _ -> vm.removeRule(pos) }
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
            showAddRuleTypeDialog()
        }

        return view
    }

    // ==================== 第一步：策略类型 ====================

    private fun showAddRuleTypeDialog() {
        if (!vm.cloudManager.isConnected()) {
            showLocalRuleDialog()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择策略类型")
            .setItems(
                arrayOf(
                    "📱 本地策略（ZigBee 设备直连控制）",
                    "☁️ 云平台策略（同步到 NleCloud 自动执行）"
                )
            ) { _, which ->
                when (which) {
                    0 -> showLocalRuleDialog()
                    1 -> showCloudTaskModeDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 第二步：任务模式 ====================

    private fun showCloudTaskModeDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择任务模式")
            .setItems(
                arrayOf(
                    "⏰ 条件任务（当传感器满足条件时自动执行）",
                    "📅 定时任务（在指定时间自动执行）"
                )
            ) { _, which ->
                when (which) {
                    0 -> showCloudConditionDialog()
                    1 -> showCloudTimedRuleDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 本地 ZigBee ====================

    private fun showLocalRuleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_rule, null)
        val etName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val spSensor = dialogView.findViewById<Spinner>(R.id.spSensorType)
        val spOperator = dialogView.findViewById<Spinner>(R.id.spOperator)
        val etThreshold = dialogView.findViewById<EditText>(R.id.etThreshold)
        val spDevice = dialogView.findViewById<Spinner>(R.id.spActionDevice)
        val spCmd = dialogView.findViewById<Spinner>(R.id.spActionCmd)
        val etActionNode = dialogView.findViewById<EditText>(R.id.etActionNode)

        try { dialogView.findViewById<View>(R.id.layoutNode)?.visibility = View.VISIBLE } catch (_: Exception) {}

        val sensorValues = arrayOf("temp", "humi", "light", "pir", "flame", "gas")
        val operatorValues = arrayOf(">", "<", "==", ">=", "<=")
        val deviceValues = arrayOf("light", "fan")
        val cmdValues = arrayOf("on", "off")

        spSensor.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            arrayOf("温度 (temp)", "湿度 (humi)", "光照 (light)", "人体 (pir)", "火焰 (flame)", "可燃气 (gas)"))
        spOperator.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            arrayOf("大于 (>)", "小于 (<)", "等于 (==)", "大于等于 (>=)", "小于等于 (<=)"))
        spDevice.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            arrayOf("灯光 (light)", "风扇 (fan)"))
        spCmd.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            arrayOf("开启 (on)", "关闭 (off)"))

        spDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                etActionNode.setText(when (deviceValues[position]) {
                    "light" -> "0x0004"; "fan" -> "0x0005"; else -> "0x0000"
                })
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spSensor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (sensorValues[position]) {
                    "flame" -> { etThreshold.setText("1"); spOperator.setSelection(2); spDevice.setSelection(0); spCmd.setSelection(1) }
                    "gas" -> { etThreshold.setText("400"); spOperator.setSelection(0); spDevice.setSelection(0); spCmd.setSelection(1) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加本地策略")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                try {
                    vm.addRule(Rule(
                        name = etName.text.toString().ifBlank { "规则" },
                        sensorType = sensorValues[spSensor.selectedItemPosition],
                        operator = operatorValues[spOperator.selectedItemPosition],
                        threshold = etThreshold.text.toString().toFloatOrNull() ?: 0f,
                        actionNode = etActionNode.text.toString().trim().ifBlank { "0x0000" },
                        actionDevice = deviceValues[spDevice.selectedItemPosition],
                        actionCmd = cmdValues[spCmd.selectedItemPosition],
                        enabled = true, isCloud = false
                    ))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 云平台 - 条件任务 ====================

    private fun showCloudConditionDialog() {
        // ★ 用设备列表（有正确DeviceID），不用sensorMetaCache
        val sensorItems = vm.getCloudSensorDeviceItems()
        val actuatorItems = vm.getCloudActuatorSwitches()

        if (sensorItems.isEmpty() || actuatorItems.isEmpty()) {
            Toast.makeText(requireContext(), "传感器或执行器数据加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_rule, null)
        val etName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val spSensor = dialogView.findViewById<Spinner>(R.id.spSensorType)
        val spOperator = dialogView.findViewById<Spinner>(R.id.spOperator)
        val etThreshold = dialogView.findViewById<EditText>(R.id.etThreshold)
        val spDevice = dialogView.findViewById<Spinner>(R.id.spActionDevice)
        val spCmd = dialogView.findViewById<Spinner>(R.id.spActionCmd)

        // 隐藏规则名称输入框
        etName.visibility = View.GONE
        try { (etName.parent as? View)?.visibility = View.GONE } catch (_: Exception) {}
        try { dialogView.findViewById<View>(R.id.layoutNode)?.visibility = View.GONE } catch (_: Exception) {}

        val operatorValues = arrayOf(">", "<", "==", ">=", "<=")
        val operatorLabels = arrayOf("大于 (>)", "小于 (<)", "等于 (==)", "大于等于 (>=)", "小于等于 (<=)")

        // ★ 传感器列表：显示 名称[设备ID]
        spSensor.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            sensorItems.map { "${it.name} [${it.cloudDeviceId}]" }.toTypedArray())
        // ★ 执行器列表：显示 名称[设备ID]
        spDevice.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            actuatorItems.map { "${it.name} [${it.cloudDeviceId}]" }.toTypedArray())
        spOperator.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, operatorLabels)
        spCmd.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("开启", "关闭"))

        spSensor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position !in sensorItems.indices) return
                val tag = (sensorItems[position].cloudApiTag ?: "").lowercase()
                when {
                    "flame" in tag -> { etThreshold.setText("1"); spOperator.setSelection(2); spCmd.setSelection(1) }
                    "gas" in tag -> { etThreshold.setText("400"); spOperator.setSelection(0); spCmd.setSelection(1) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加条件任务")
            .setView(dialogView)
            .setPositiveButton("确定并同步") { _, _ ->
                try {
                    val selSensor = sensorItems[spSensor.selectedItemPosition]
                    val selActuator = actuatorItems[spDevice.selectedItemPosition]
                    val operator = operatorValues[spOperator.selectedItemPosition]
                    val threshold = etThreshold.text.toString().toFloatOrNull() ?: 0f
                    val actionValue = if (spCmd.selectedItemPosition == 0) "1" else "0"

                    // 自动生成规则名称
                    val sName = selSensor.name
                    val aName = selActuator.name
                    val cmdText = if (actionValue == "1") "开启" else "关闭"
                    val autoName = "当${sName}${operator}${threshold}时${cmdText}${aName}"

                    vm.addRule(Rule(
                        name = autoName,
                        operator = operator,
                        threshold = threshold,
                        enabled = true, isCloud = true, isTimedTask = false,
                        cloudProjectId = vm.getSavedProjectId(),
                        cloudSensorDeviceId = selSensor.cloudDeviceId ?: 0,
                        cloudSensorApiTag = selSensor.cloudApiTag ?: "",
                        cloudSensorName = selSensor.name ?: "",
                        cloudConditionOperator = spOperator.selectedItemPosition + 1,
                        cloudActuatorDeviceId = selActuator.cloudDeviceId ?: 0,
                        cloudActuatorApiTag = selActuator.cloudApiTag ?: "",
                        cloudActuatorName = selActuator.name ?: "",
                        cloudActionValue = actionValue
                    ))
                    Toast.makeText(requireContext(), "正在同步到云平台...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 云平台 - 定时任务 ====================

    private fun showCloudTimedRuleDialog() {
        val actuators = vm.getCloudActuators()
        if (actuators.isEmpty()) {
            Toast.makeText(requireContext(), "执行器数据加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        timeSlots.clear()
        timeSlots.add(RunTimeSlot(8, 0))

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_timed_rule, null)
        val etName = dialogView.findViewById<EditText>(R.id.etTimedRuleName)
        val spDevice = dialogView.findViewById<Spinner>(R.id.spTimedDevice)
        val spAction = dialogView.findViewById<Spinner>(R.id.spTimedAction)
        val spPeriod = dialogView.findViewById<Spinner>(R.id.spPeriod)
        val layoutDay = dialogView.findViewById<LinearLayout>(R.id.layoutDay)
        val spDay = dialogView.findViewById<Spinner>(R.id.spDay)
        slotContainer = dialogView.findViewById(R.id.layoutTimeSlots)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAddSlot)

        // 执行设备
        spDevice.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            actuators.map { "${it.Name.ifBlank { it.ApiTag }} (${vm.getDeviceName(it.DeviceID)})" }.toTypedArray())

        // 执行动作
        spAction.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("开启", "关闭"))

        // 重复周期
        spPeriod.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            arrayOf("每日", "每周", "每月"))

        spPeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> layoutDay.visibility = View.GONE
                    1 -> {
                        layoutDay.visibility = View.VISIBLE
                        spDay.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                            arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"))
                        spDay.setSelection(0)
                    }
                    2 -> {
                        layoutDay.visibility = View.VISIBLE
                        spDay.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                            Array(31) { "${it + 1}日" })
                        spDay.setSelection(0)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 构建时间段
        rebuildTimeSlots()

        btnAdd.setOnClickListener {
            timeSlots.add(RunTimeSlot(8, 0))
            rebuildTimeSlots()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加定时任务")
            .setView(dialogView)
            .setPositiveButton("确定并同步") { _, _ ->
                try {
                    if (actuators.isEmpty()) {
                        Toast.makeText(requireContext(), "缺少执行器", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // 从 UI 读取时间段最终值
                    readTimeSlotsFromUI()

                    if (timeSlots.isEmpty()) {
                        Toast.makeText(requireContext(), "请至少添加一个时间", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val selActuator = actuators[spDevice.selectedItemPosition]
                    val period = spPeriod.selectedItemPosition
                    val day = when (period) {
                        0 -> 0
                        1 -> spDay.selectedItemPosition + 1
                        2 -> spDay.selectedItemPosition + 1
                        else -> 0
                    }

                    vm.addRule(Rule(
                        name = etName.text.toString().ifBlank { "定时任务" },
                        operator = ">",
                        threshold = 0f,
                        enabled = true, isCloud = true, isTimedTask = true,
                        cloudProjectId = vm.getSavedProjectId(),
                        cloudActuatorDeviceId = selActuator.DeviceID,
                        cloudActuatorApiTag = selActuator.ApiTag,
                        cloudActuatorName = selActuator.Name,
                        cloudActionValue = if (spAction.selectedItemPosition == 0) "1" else "0",
                        runTimePeriod = period,
                        runTimeDay = day,
                        runTimeSlots = timeSlots.toList()
                    ))
                    Toast.makeText(requireContext(), "正在同步到云平台...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 时间段管理 ====================

    private fun rebuildTimeSlots() {
        slotContainer.removeAllViews()
        for ((index, slot) in timeSlots.withIndex()) {
            val entryView = layoutInflater.inflate(R.layout.item_timed_entry, slotContainer, false)
            setupSlotView(entryView, slot, index)
            slotContainer.addView(entryView)
        }
    }

    private fun setupSlotView(view: View, slot: RunTimeSlot, index: Int) {
        val etHour = view.findViewById<EditText>(R.id.etSlotHour)
        val etMinute = view.findViewById<EditText>(R.id.etSlotMinute)
        val tvHint = view.findViewById<TextView>(R.id.tvSlotHint)
        val btnDelete = view.findViewById<ImageButton>(R.id.btnDeleteSlot)

        etHour.setText(String.format("%02d", slot.hour))
        etMinute.setText(String.format("%02d", slot.minute))
        tvHint.text = "时间段 ${index + 1}"

        btnDelete.setOnClickListener {
            if (timeSlots.size > 1) {
                readTimeSlotsFromUI()
                timeSlots.removeAt(index)
                rebuildTimeSlots()
            } else {
                Toast.makeText(requireContext(), "至少保留一个时间", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readTimeSlotsFromUI() {
        for (i in 0 until slotContainer.childCount) {
            if (i >= timeSlots.size) break
            val slot = timeSlots[i]
            val child = slotContainer.getChildAt(i)
            val etHour = child.findViewById<EditText>(R.id.etSlotHour)
            val etMinute = child.findViewById<EditText>(R.id.etSlotMinute)

            slot.hour = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 0
            slot.minute = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
        }
    }
}


// ==================== Adapter ====================

class RuleAdapter(
    private val onToggle: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onRetry: (Int) -> Unit
) : RecyclerView.Adapter<RuleAdapter.ViewHolder>() {

    private var items: List<Rule> = emptyList()

    private val sensorNameMap = mapOf(
        "temp" to "温度", "humi" to "湿度", "light" to "光照",
        "pir" to "人体", "flame" to "火焰", "gas" to "可燃气"
    )
    private val deviceNameMap = mapOf("light" to "灯光", "fan" to "风扇")
    private val periodNames = arrayOf("每日", "每周", "每月")
    private val weekDays = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun submitList(list: List<Rule>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false)
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
            val tag = when {
                rule.isTimedTask -> "⏰ "
                rule.isCloud -> "☁️ "
                else -> "📱 "
            }
            tvName.text = "$tag${rule.name}"

            val devName: String
            val cmdName: String
            if (rule.isCloud) {
                devName = rule.cloudActuatorName.ifBlank { rule.cloudActuatorApiTag }
                cmdName = if (rule.cloudActionValue == "1") "开启" else "关闭"
            } else {
                devName = deviceNameMap[rule.actionDevice] ?: rule.actionDevice
                cmdName = if (rule.actionCmd == "on") "开启" else "关闭"
            }

            tvDesc.text = if (rule.isTimedTask) {
                val periodName = periodNames.getOrElse(rule.runTimePeriod) { "每日" }
                val dayPart = when (rule.runTimePeriod) {
                    1 -> weekDays.getOrElse(rule.runTimeDay) { "" }
                    2 -> "${rule.runTimeDay}日"
                    else -> ""
                }
                val timePart = rule.runTimeSlots.joinToString("、") { slot ->
                    String.format("%02d:%02d", slot.hour, slot.minute)
                }
                "$periodName$dayPart $timePart → $cmdName$devName"
            } else if (rule.isCloud) {
                val sName = rule.cloudSensorName.ifBlank { rule.cloudSensorApiTag }
                "当 $sName ${rule.operator} ${rule.threshold} 时，$cmdName$devName"
            } else {
                val sName = sensorNameMap[rule.sensorType] ?: rule.sensorType
                "当 $sName ${rule.operator} ${rule.threshold} 时，$cmdName$devName"
            }

            switch.setOnCheckedChangeListener(null)
            switch.isChecked = rule.enabled
            switch.setOnCheckedChangeListener { _, _ ->
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onToggle(pos)
            }

            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(pos)
            }

            card.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && (rule.syncStatus == 2 || rule.syncStatus == 3)) {
                    onRetry(pos)
                    Toast.makeText(itemView.context, "正在重试...", Toast.LENGTH_SHORT).show()
                }
                true
            }

            val dotColor: Int
            val syncText: String

            when (rule.syncStatus) {
                0 -> {
                    dotColor = 0xFFFF5252.toInt()
                    syncText = when {
                        rule.isTimedTask -> "等待创建定时任务"
                        rule.isCloud -> "等待创建云策略"
                        else -> "待同步"
                    }
                }
                1 -> {
                    dotColor = 0xFF4CAF50.toInt()
                    syncText = when {
                        rule.isTimedTask -> "⏰ 定时任务已生效" + if (rule.cloudStrategyId > 0) " (ID:${rule.cloudStrategyId})" else ""
                        rule.isCloud -> "☁️ 云策略已生效" + if (rule.cloudStrategyId > 0) " (ID:${rule.cloudStrategyId})" else ""
                        else -> "已同步"
                    }
                }
                2 -> {
                    dotColor = 0xFFFF5252.toInt()
                    val a = when {
                        rule.isTimedTask -> "创建定时任务"
                        rule.isCloud -> "创建云策略"
                        else -> "同步"
                    }
                    syncText = "${a}失败，${rule.retryCount}/10 次，1分钟后重试"
                }
                3 -> {
                    dotColor = 0xFFBDBDBD.toInt()
                    val a = when {
                        rule.isTimedTask -> "创建定时任务"
                        rule.isCloud -> "创建云策略"
                        else -> "同步"
                    }
                    syncText = "${a}失败，已重试10次，长按可重试"
                }
                4 -> {
                    dotColor = 0xFFFFEB3B.toInt()
                    syncText = when {
                        rule.isTimedTask -> "正在创建定时任务..."
                        rule.isCloud -> "正在创建云策略..."
                        else -> "正在上传..."
                    }
                }
                else -> {
                    dotColor = 0xFF9E9E9E.toInt()
                    syncText = ""
                }
            }

            viewDot.background.setTint(dotColor)
            tvSyncInfo.text = syncText
            card.alpha = if (rule.syncStatus == 3) 0.4f else 1.0f
        }
    }
}
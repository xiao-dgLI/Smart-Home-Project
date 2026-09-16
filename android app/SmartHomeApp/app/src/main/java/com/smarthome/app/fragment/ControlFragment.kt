package com.smarthome.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R
import com.smarthome.app.adapter.ControlAdapter
import com.smarthome.app.model.ControlSwitch
import com.smarthome.app.viewmodel.ControlViewModel
import kotlinx.coroutines.launch

class ControlFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private val controlVm: ControlViewModel by viewModels()
    private lateinit var adapter: ControlAdapter
    private lateinit var recyclerView: RecyclerView

    private var selectedIcon = "💡"
    private val iconViews = mutableListOf<TextView>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_control, container, false)

        vm = (requireActivity() as MainActivity).viewModel

        recyclerView = view.findViewById(R.id.recyclerControl)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = ControlAdapter(
            onToggle = { index ->
                val switches = vm.switches.value ?: return@ControlAdapter
                if (index !in switches.indices) return@ControlAdapter
                val sw = switches[index]
                val newIsOn = !sw.isOn
                adapter.animateToggle(index, newIsOn, recyclerView)
                val accepted = controlVm.toggleSwitchSilent(vm, index)
                if (!accepted) {
                    adapter.animateToggle(index, !newIsOn, recyclerView)
                    Toast.makeText(requireContext(), "设备离线，操作失败", Toast.LENGTH_SHORT).show()
                }
            },
            onEdit = { index ->
                val sw = vm.switches.value?.getOrNull(index) ?: return@ControlAdapter
                if (sw.isCloud) {
                    Toast.makeText(requireContext(), "云平台设备不可编辑", Toast.LENGTH_SHORT).show()
                } else {
                    showEditNameDialog(index)
                }
            },
            onEditOnCmd = { index ->
                val sw = vm.switches.value?.getOrNull(index) ?: return@ControlAdapter
                if (sw.isCloud) {
                    Toast.makeText(requireContext(), "云平台设备指令由平台管理", Toast.LENGTH_SHORT).show()
                } else {
                    showEditCmdDialog(index, true)
                }
            },
            onEditOffCmd = { index ->
                val sw = vm.switches.value?.getOrNull(index) ?: return@ControlAdapter
                if (sw.isCloud) {
                    Toast.makeText(requireContext(), "云平台设备指令由平台管理", Toast.LENGTH_SHORT).show()
                } else {
                    showEditCmdDialog(index, false)
                }
            },
            onDelete = { index ->
                val sw = vm.switches.value?.getOrNull(index) ?: return@ControlAdapter
                showDeleteConfirm(index)
            },
            onCorrect = { index ->
                val sw = vm.switches.value?.getOrNull(index) ?: return@ControlAdapter
                if (sw.isCloud) {
                    Toast.makeText(requireContext(), "云平台设备无需纠错", Toast.LENGTH_SHORT).show()
                } else {
                    showCorrectDialog(index)
                }
            }
        )

        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.switches.collect { list ->
                    adapter.submitList(list.toList())
                }
            }
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddSwitch).setOnClickListener {
            showChooseAddSwitchTypeDialog()
        }

        return view
    }


     // 选择添加开关类型对话框

    private fun showChooseAddSwitchTypeDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择添加方式")
            .setItems(
                arrayOf(
                    "添加本地开关",
                    "创建云平台执行器"
                )
            ) { _, which ->
                when (which) {
                    0 -> showAddSwitchDialog()
                    1 -> showCreateCloudActuatorDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }


     // 创建云平台执行器对话框

    private fun showCreateCloudActuatorDialog() {
        val devices = vm.getSavedDeviceList()
        if (devices.isEmpty()) {
            Toast.makeText(requireContext(), "暂无云平台设备，请先连接项目", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_create_cloud_actuator, null)

        val spDevice = dialogView.findViewById<android.widget.Spinner>(R.id.spDevice)
        val etApiTag = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etApiTag)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etName)
        val spOperType = dialogView.findViewById<android.widget.Spinner>(R.id.spOperType)

        // 设备列表
        val deviceNames = devices.map { "${it.Name} (ID: ${it.DeviceID})" }.toTypedArray()
        spDevice.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, deviceNames)

        // 操作类型（执行器专用）
        val operTypes = arrayOf("开关型 (1)", "开关停型 (2)", "按钮型 (3)", "刻度型 (4)")
        spOperType.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, operTypes)
        spOperType.setSelection(0) // 默认开关型

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("创建云平台执行器")
            .setView(dialogView)
            .setPositiveButton("创建") { _, _ ->
                val deviceIndex = spDevice.selectedItemPosition
                if (deviceIndex !in devices.indices) {
                    Toast.makeText(requireContext(), "请选择设备", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val apiTag = etApiTag.text.toString().trim()
                val name = etName.text.toString().trim()
                val operTypeIndex = spOperType.selectedItemPosition

                if (apiTag.isBlank()) {
                    Toast.makeText(requireContext(), "请输入执行器标识", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "请输入执行器名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val device = devices[deviceIndex]
                val operType = operTypeIndex + 1 // 1:开关型, 2:开关停型, 3:按钮型, 4:刻度型

                // 检测重复并自动编号
                val existingNames = mutableSetOf<String>()
                val existingApiTags = mutableSetOf<String>()
                for (item in controlVm.getCloudSensorDeviceItems(vm)) {
                    existingNames.add(item.name)
                    item.cloudApiTag?.let { existingApiTags.add(it) }
                }
                for (sw in controlVm.getCloudActuatorSwitches(vm)) {
                    existingNames.add(sw.name)
                    existingApiTags.add(sw.cloudApiTag)
                }

                var finalName = name
                var finalApiTag = apiTag
                var nameChanged = false
                var apiTagChanged = false

                if (name in existingNames) {
                    var counter = 2
                    while ("${name}_$counter" in existingNames) counter++
                    finalName = "${name}_$counter"
                    nameChanged = true
                }
                if (apiTag in existingApiTags) {
                    var counter = 2
                    while ("${apiTag}_$counter" in existingApiTags) counter++
                    finalApiTag = "${apiTag}_$counter"
                    apiTagChanged = true
                }

                if (nameChanged || apiTagChanged) {
                    val message = buildString {
                        if (nameChanged) append("名称「$name」已存在，建议改为「$finalName」\n")
                        if (apiTagChanged) append("标识名「$apiTag」已存在，建议改为「$finalApiTag」")
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("检测到重复")
                        .setMessage(message)
                        .setPositiveButton("使用建议名称创建") { _, _ ->
                            doCreateCloudActuator(device, finalName, finalApiTag, operType)
                        }
                        .setNeutralButton("自行修改") { _, _ -> }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    doCreateCloudActuator(device, finalName, finalApiTag, operType)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }


     // 执行创建云平台执行器
    private fun doCreateCloudActuator(
        device: com.smarthome.app.cloud.DeviceBaseInfo,
        name: String, apiTag: String, operType: Int
    ) {
        Toast.makeText(requireContext(), "正在创建执行器...", Toast.LENGTH_SHORT).show()

        controlVm.createCloudActuator(
            mainVm = vm,
            deviceId = device.DeviceID,
            apiTag = apiTag,
            name = name,
            operType = operType
        ) { success, message ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(requireContext(), "执行器创建成功: $name", Toast.LENGTH_SHORT).show()
                    // 刷新云平台设备列表
                    vm.refreshCloudDevices()
                } else {
                    Toast.makeText(requireContext(), "创建失败: $message", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAddSwitchDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_switch, null)
        setupIconPicker(dialogView)

        val etName = dialogView.findViewById<EditText>(R.id.etName)
        val etNode = dialogView.findViewById<EditText>(R.id.etNodeAddr)
        val etOnCmd = dialogView.findViewById<EditText>(R.id.etOnCmd)
        val etOffCmd = dialogView.findViewById<EditText>(R.id.etOffCmd)

        etName.setText("")
        etNode.setText("0x0004")
        etOnCmd.setText("""{"type":"control","node":"0x0004","action":"on"}""")
        etOffCmd.setText("""{"type":"control","node":"0x0004","action":"off"}""")

        etNode.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val addr = etNode.text.toString().ifBlank { "0x0004" }
                etOnCmd.setText("""{"type":"control","node":"$addr","action":"on"}""")
                etOffCmd.setText("""{"type":"control","node":"$addr","action":"off"}""")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加开关设备")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text.toString().ifBlank { "未命名设备" }
                val nodeAddr = etNode.text.toString().ifBlank { "0x0004" }
                val sw = ControlSwitch(
                    name = name,
                    icon = selectedIcon,
                    nodeAddr = nodeAddr,
                    onCommand = etOnCmd.text.toString().ifBlank {
                        """{"type":"control","node":"$nodeAddr","action":"on"}"""
                    },
                    offCommand = etOffCmd.text.toString().ifBlank {
                        """{"type":"control","node":"$nodeAddr","action":"off"}"""
                    }
                )
                controlVm.addSwitch(vm, sw)
                Toast.makeText(requireContext(), "已添加: ${sw.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditNameDialog(index: Int) {
        val switches = vm.switches.value ?: return
        if (index !in switches.indices) return
        val sw = switches[index]

        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_switch, null)
        setupIconPicker(dialogView, sw.icon)

        val etName = dialogView.findViewById<EditText>(R.id.etName)
        val etNode = dialogView.findViewById<EditText>(R.id.etNodeAddr)
        val etOnCmd = dialogView.findViewById<EditText>(R.id.etOnCmd)
        val etOffCmd = dialogView.findViewById<EditText>(R.id.etOffCmd)

        etName.setText(sw.name)
        etNode.setText(sw.nodeAddr)
        etOnCmd.setText(sw.onCommand)
        etOffCmd.setText(sw.offCommand)

        (etOnCmd.parent as? View)?.let { (it.parent as? View)?.visibility = View.GONE }
        (etOffCmd.parent as? View)?.let { (it.parent as? View)?.visibility = View.GONE }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑设备")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                sw.name = etName.text.toString().ifBlank { sw.name }
                sw.icon = selectedIcon
                sw.nodeAddr = etNode.text.toString().ifBlank { sw.nodeAddr }
                controlVm.updateSwitch(vm, index, sw)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditCmdDialog(index: Int, isOnCmd: Boolean) {
        val switches = vm.switches.value ?: return
        if (index !in switches.indices) return
        val sw = switches[index]

        val input = EditText(requireContext()).apply {
            setText(if (isOnCmd) sw.onCommand else sw.offCommand)
            setPadding(60, 40, 60, 40)
            textSize = 13f
            isSingleLine = false
            minLines = 3
            maxLines = 6
        }

        val title = if (isOnCmd) "编辑开启指令" else "编辑关闭指令"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage("当前设备: ${sw.name}\n输入自定义 JSON 指令:")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val cmd = input.text.toString().trim()
                if (cmd.isNotBlank()) {
                    if (isOnCmd) sw.onCommand = cmd else sw.offCommand = cmd
                    controlVm.updateSwitch(vm, index, sw)
                    Toast.makeText(requireContext(), "指令已更新", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }


    //纠错：不发送指令，只修改本地卡片的开关状态
    private fun showCorrectDialog(index: Int) {
        val switches = vm.switches.value ?: return
        if (index !in switches.indices) return
        val sw = switches[index]

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("纠错")
            .setMessage(
                "设备: ${sw.name}\n" +
                        "当前显示: ${if (sw.isOn) "已开启" else "已关闭"}\n" +
                        "将切换为: ${if (sw.isOn) "已关闭" else "已开启"}\n\n" +
                        "注意: 不会发送指令到设备，仅修改本地显示状态"
            )
            .setPositiveButton("确认纠错") { _, _ ->
                // 只翻转本地状态，不发送指令
                controlVm.correctSwitchState(vm, index)
                adapter.updateStateFromDevice(index, sw.isOn, recyclerView)
                Toast.makeText(
                    requireContext(),
                    "已纠错: ${sw.name} → ${if (sw.isOn) "开启" else "关闭"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirm(index: Int) {
        val switches = adapter.getItems()
        if (index !in switches.indices) return
        val sw = switches[index]

        if (sw.isCloud) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除设备")
                .setPositiveButton("同时删除云平台执行器") { _, _ ->
                    val devId = sw.cloudDeviceId
                    val apiTag = sw.cloudApiTag
                    Toast.makeText(requireContext(), "正在删除云平台执行器...", Toast.LENGTH_SHORT).show()
                    controlVm.deleteCloudActuator(devId, apiTag) { success ->
                        activity?.runOnUiThread {
                            controlVm.removeSwitch(vm, index)
                            val tip = if (success) "已从云平台和本地删除: ${sw.name}"
                            else "云平台删除失败，已从本地移除: ${sw.name}"
                            Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNeutralButton("仅删除本地卡片") { _, _ ->
                    controlVm.removeSwitch(vm, index)
                    Toast.makeText(requireContext(), "已从本地删除: ${sw.name}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除设备")
                .setMessage("确定要删除 \"${sw.name}\" 吗？")
                .setPositiveButton("删除") { _, _ ->
                    controlVm.removeSwitch(vm, index)
                    Toast.makeText(requireContext(), "已删除: ${sw.name}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupIconPicker(dialogView: View, defaultIcon: String = "💡") {
        selectedIcon = defaultIcon
        iconViews.clear()

        val icons = listOf("💡", "🌀", "📺", "🔌", "🚪", "🔊", "🌡", "💧", "☀", "🔒", "🚗", "⚡")
        val ids = listOf(
            R.id.icon1, R.id.icon2, R.id.icon3, R.id.icon4,
            R.id.icon5, R.id.icon6, R.id.icon7, R.id.icon8,
            R.id.icon9, R.id.icon10, R.id.icon11, R.id.icon12
        )

        for (i in ids.indices) {
            val tv = dialogView.findViewById<TextView>(ids[i])
            iconViews.add(tv)

            tv.background = if (icons[i] == selectedIcon) {
                resources.getDrawable(android.R.drawable.editbox_background, null)
            } else {
                null
            }

            tv.setOnClickListener {
                selectedIcon = icons[i]
                for (j in iconViews.indices) {
                    iconViews[j].background = if (j == i) {
                        resources.getDrawable(android.R.drawable.editbox_background, null)
                    } else {
                        null
                    }
                }
            }
        }
    }
}
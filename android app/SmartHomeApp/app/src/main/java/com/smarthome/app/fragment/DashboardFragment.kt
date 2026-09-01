package com.smarthome.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R
import com.smarthome.app.adapter.SensorCardAdapter
import com.smarthome.app.viewmodel.DashboardViewModel
import com.smarthome.app.databinding.FragmentDashboardBinding
import com.smarthome.app.model.SensorDeviceItem
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var sensorAdapter: SensorCardAdapter
    private lateinit var vm: MainViewModel
    private val dashboardVm: DashboardViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = (requireActivity() as MainActivity).viewModel

        sensorAdapter = SensorCardAdapter(mutableListOf()) { position ->
            showDeleteSensorDialog(position)
        }
        binding.rvSensorCards.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sensorAdapter
            isNestedScrollingEnabled = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.sensorDevices.collect { devices ->
                        sensorAdapter.setItems(devices)
                        binding.rvSensorCards.post {
                            binding.rvSensorCards.requestLayout()
                        }
                        binding.tvEmptyHint.visibility =
                            if (devices.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    vm.sensorData.collect { data ->
                        sensorAdapter.updateSensorData(data)
                    }
                }
                launch {
                    vm.cloudSensorValues.collect { values ->
                        sensorAdapter.updateCloudValues(values)
                    }
                }
                launch {
                    vm.logMessages.collect { logs ->
                        binding.tvLogs.text = logs.take(50).joinToString("\n")
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_UP)
                        }
                    }
                }
            }
        }

        binding.btnRefreshCloud.setOnClickListener {
            val isConnected = vm.cloudConnected.value
            if (!isConnected) {
                Toast.makeText(requireContext(), "请先连接云平台", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.refreshCloudDevices()
            Toast.makeText(requireContext(), "正在更新...", Toast.LENGTH_SHORT).show()
        }

        binding.btnAddSensor.setOnClickListener {
            showAddTypeDialog()
        }

        binding.btnSendCmd.setOnClickListener {
            val cmd = binding.etSendCmd.text.toString().trim()
            if (cmd.isBlank()) {
                Toast.makeText(requireContext(), "请输入指令", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.sendRawCmd(cmd)
            binding.etSendCmd.text.clear()
        }

        binding.btnClearLogs.setOnClickListener {
            vm.clearLogs()
            Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 添加传感器类型选择对话框
     */
    private fun showAddTypeDialog() {
        val isCloudConnected = vm.cloudConnected.value == true

        if (!isCloudConnected) {
            showAddLocalSensorDialog()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择添加方式")
            .setItems(
                arrayOf(
                    "📱 添加本地传感器（预设类型）",
                    "➕ 创建云平台传感器"
                )
            ) { _, which ->
                when (which) {
                    0 -> showAddLocalSensorDialog()
                    1 -> showCreateCloudSensorDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 添加本地传感器对话框
     */
    private fun showAddLocalSensorDialog() {
        val allTypes = SensorDeviceItem.getAllTypes()
        val currentTypes = sensorAdapter.getItems()
            .filter { !it.isCloud }
            .map { it.sensorType }
            .toSet()
        val available = allTypes.filter { it.sensorType !in currentTypes }

        if (available.isEmpty()) {
            Toast.makeText(requireContext(), "所有本地传感器已添加", Toast.LENGTH_SHORT).show()
            return
        }

        val displayNames = available.map { "${it.icon}  ${it.name}" }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("选择要添加的传感器")
            .setItems(displayNames) { _, which ->
                dashboardVm.addSensorDevice(vm, available[which])
                Toast.makeText(requireContext(), "已添加: ${available[which].name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 创建云平台传感器对话框
     */
    private fun showCreateCloudSensorDialog() {
        val devices = vm.getSavedDeviceList()
        if (devices.isEmpty()) {
            Toast.makeText(requireContext(), "暂无云平台设备，请先连接项目", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_create_sensor, null)

        val spDevice = dialogView.findViewById<Spinner>(R.id.spDevice)
        val spSensorType = dialogView.findViewById<Spinner>(R.id.spSensorType)
        val etApiTag = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etApiTag)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etName)
        val etUnit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUnit)
        val spDataType = dialogView.findViewById<Spinner>(R.id.spDataType)
        val spTransType = dialogView.findViewById<Spinner>(R.id.spTransType)

        // 隐藏传输类型选择（监测页面固定为传感器 TransType=0）
        spTransType.visibility = View.GONE
        // 查找并隐藏传输类型的标签TextView
        val transTypeLabel = dialogView.findViewById<TextView>(R.id.tvTransType)
        if (transTypeLabel != null) {
            transTypeLabel.visibility = View.GONE
        }

        // 设备列表
        val deviceNames = devices.map { "${it.Name} (ID: ${it.DeviceID})" }.toTypedArray()
        spDevice.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, deviceNames)

        // 传感器类型预设
        val sensorTypes = arrayOf(
            "温度 (temp)", "湿度 (humi)", "光照 (light)",
            "人体检测 (pir)", "火焰检测 (flame)", "可燃气 (gas)",
            "自定义"
        )
        val sensorApiTags = arrayOf("temp", "humi", "light", "pir", "flame", "gas", "")
        val sensorUnits = arrayOf("°C", "%RH", "lux", "", "", "", "")
        spSensorType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sensorTypes)

        // 数据类型
        val dataTypes = arrayOf("整数 (int)", "浮点数 (float)", "字符串 (string)", "布尔 (bool)")
        val dataTypeValues = arrayOf("int", "float", "string", "bool")
        spDataType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, dataTypes)

        // 传输类型（隐藏，固定为传感器）
        val transTypes = arrayOf("传感器 (0)", "执行器 (1)")
        spTransType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, transTypes)
        spTransType.setSelection(0) // 固定选择传感器

        // 传感器类型选择变化时自动填充
        spSensorType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < sensorApiTags.size - 1) {
                    etApiTag.setText(sensorApiTags[position])
                    etName.setText(sensorTypes[position].substringBefore(" ("))
                    etUnit.setText(sensorUnits[position])
                } else {
                    etApiTag.setText("")
                    etName.setText("")
                    etUnit.setText("")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("创建云平台传感器")
            .setView(dialogView)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .show()
            .also { dialog ->
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val deviceIndex = spDevice.selectedItemPosition
                    if (deviceIndex !in devices.indices) {
                        Toast.makeText(requireContext(), "请选择设备", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val apiTag = etApiTag.text.toString().trim()
                    val name = etName.text.toString().trim()
                    val unit = etUnit.text.toString().trim()
                    val dataTypeIndex = spDataType.selectedItemPosition
                    val transType = spTransType.selectedItemPosition

                    if (apiTag.isBlank()) {
                        Toast.makeText(requireContext(), "请输入ApiTag", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (name.isBlank()) {
                        Toast.makeText(requireContext(), "请输入传感器名称", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val device = devices[deviceIndex]
                    val sensorType = dataTypeValues[dataTypeIndex]

                    // 收集已有设备的名称和标识名
                    val existingNames = mutableSetOf<String>()
                    val existingApiTags = mutableSetOf<String>()
                    for (item in dashboardVm.getCloudSensorDeviceItems(vm)) {
                        existingNames.add(item.name)
                        item.cloudApiTag?.let { existingApiTags.add(it) }
                    }
                    for (sw in vm.getCloudActuatorSwitches()) {
                        existingNames.add(sw.name)
                        existingApiTags.add(sw.cloudApiTag)
                    }

                    // 检测重复并自动编号
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
                        // 有重名，弹出确认对话框
                        showDuplicateConfirmDialog(
                            device, finalName, finalApiTag, name, apiTag,
                            unit, sensorType, transType
                        )
                    } else {
                        // 无重名，直接创建
                        dialog.dismiss()
                        doCreateCloudSensor(device, finalName, finalApiTag, unit, sensorType, transType)
                    }
                }
            }
    }

    /**
     * 名称或标识名重复时的确认对话框
     */
    private fun showDuplicateConfirmDialog(
        device: com.smarthome.app.cloud.DeviceBaseInfo,
        suggestedName: String, suggestedApiTag: String,
        originalName: String, originalApiTag: String,
        unit: String, sensorType: String, transType: Int
    ) {
        val message = buildString {
            if (suggestedName != originalName) {
                append("名称「$originalName」已存在，建议改为「$suggestedName」\n")
            }
            if (suggestedApiTag != originalApiTag) {
                append("标识名「$originalApiTag」已存在，建议改为「$suggestedApiTag」")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("检测到重复")
            .setMessage(message)
            .setPositiveButton("使用建议名称创建") { _, _ ->
                doCreateCloudSensor(device, suggestedName, suggestedApiTag, unit, sensorType, transType)
            }
            .setNeutralButton("自行修改") { _, _ ->
                // 关闭对话框，用户回到创建界面修改
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行创建云平台传感器
     */
    private fun doCreateCloudSensor(
        device: com.smarthome.app.cloud.DeviceBaseInfo,
        name: String, apiTag: String, unit: String,
        sensorType: String, transType: Int
    ) {
        Toast.makeText(requireContext(), "正在创建传感器...", Toast.LENGTH_SHORT).show()

        // 监测页面创建的是传感器，固定TransType=0（只上报）
        dashboardVm.createCloudSensor(
            mainVm = vm,
            deviceId = device.DeviceID,
            apiTag = apiTag,
            name = name,
            unit = unit,
            transType = 0, // 固定为传感器（只上报）
            operType = 0,
            sensorType = sensorType
        ) { success, message ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(requireContext(), "传感器创建成功: $name", Toast.LENGTH_SHORT).show()
                    // 创建成功后刷新云平台设备列表，使卡片及时更新
                    vm.refreshCloudDevices()
                } else {
                    Toast.makeText(requireContext(), "创建失败: $message", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteSensorDialog(position: Int) {
        val items = sensorAdapter.getItems()
        if (position !in items.indices) return
        val item = items[position]

        if (item.isCloud) {
            // 云平台传感器：三个选项
            AlertDialog.Builder(requireContext())
                .setTitle("删除传感器")
                .setMessage("确定要删除 \"${item.name}\" 吗？")
                .setPositiveButton("同时删除云平台设备") { _, _ ->
                    val devId = item.cloudDeviceId ?: 0
                    val apiTag = item.cloudApiTag ?: ""
                    Toast.makeText(requireContext(), "正在删除云平台设备...", Toast.LENGTH_SHORT).show()
                    dashboardVm.deleteCloudSensor(devId, apiTag) { success ->
                        activity?.runOnUiThread {
                            dashboardVm.removeSensorDevice(vm, item.id)
                            val tip = if (success) "已从云平台和本地删除: ${item.name}"
                            else "云平台删除失败，已从本地移除: ${item.name}"
                            Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNeutralButton("仅删除本地卡片") { _, _ ->
                    dashboardVm.removeSensorDevice(vm, item.id)
                    Toast.makeText(requireContext(), "已从本地删除: ${item.name}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 本地传感器：两个选项
            AlertDialog.Builder(requireContext())
                .setTitle("删除传感器")
                .setMessage("确定要删除 \"${item.name}\" 吗？")
                .setPositiveButton("删除") { _, _ ->
                    dashboardVm.removeSensorDevice(vm, item.id)
                    Toast.makeText(requireContext(), "已删除: ${item.name}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

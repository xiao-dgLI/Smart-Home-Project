package com.smarthome.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.adapter.SensorCardAdapter
import com.smarthome.app.databinding.FragmentDashboardBinding
import com.smarthome.app.model.SensorDeviceItem

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var sensorAdapter: SensorCardAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = (requireActivity() as MainActivity).viewModel

        // 传感器卡片
        sensorAdapter = SensorCardAdapter(mutableListOf()) { position ->
            showDeleteSensorDialog(position, vm)
        }
        binding.rvSensorCards.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sensorAdapter
            isNestedScrollingEnabled = false
        }

        // 传感器卡片列表
        vm.sensorDevices.observe(viewLifecycleOwner) { devices ->
            sensorAdapter.setItems(devices)
            binding.tvEmptyHint.visibility =
                if (devices.isEmpty()) View.VISIBLE else View.GONE
        }

        // 本地传感器数据
        vm.sensorData.observe(viewLifecycleOwner) { data ->
            sensorAdapter.updateSensorData(data)
        }

        // 云端传感器实时值
        vm.cloudSensorValues.observe(viewLifecycleOwner) { values ->
            sensorAdapter.updateCloudValues(values)
        }

        // 更新按钮：重新从云平台拉取设备并刷新卡片
        binding.btnRefreshCloud.setOnClickListener {
            val isConnected = vm.cloudConnected.value == true
            if (!isConnected) {
                Toast.makeText(requireContext(), "请先连接云平台", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.refreshCloudDevices()
            Toast.makeText(requireContext(), "正在更新...", Toast.LENGTH_SHORT).show()
        }

        // 添加按钮
        binding.btnAddSensor.setOnClickListener {
            showAddSensorDialog(vm)
        }

        // 连接状态
        vm.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.tvConnectionStatus.text = status
            binding.tvConnectionStatus.setTextColor(
                if (status.contains("已连接") || status.contains("成功"))
                    0xFF4CAF50.toInt() else 0xFFF44336.toInt()
            )
        }

        // 日志
        vm.logMessages.observe(viewLifecycleOwner) { logs ->
            binding.tvLogs.text = logs.take(50).joinToString("\n")
            binding.scrollLogs.post {
                binding.scrollLogs.fullScroll(View.FOCUS_UP)
            }
        }

        // 发送指令
        binding.btnSendCmd.setOnClickListener {
            val cmd = binding.etSendCmd.text.toString().trim()
            if (cmd.isBlank()) {
                Toast.makeText(requireContext(), "请输入指令", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.sendRawCmd(cmd)
            binding.etSendCmd.text.clear()
        }

        // 清空日志
        binding.btnClearLogs.setOnClickListener {
            vm.clearLogs()
            Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
        }
    }

    // 添加传感器对话框
    private fun showAddSensorDialog(vm: MainViewModel) {
        val allTypes = SensorDeviceItem.getAllTypes()
        val currentTypes = sensorAdapter.getItems()
            .filter { !it.isCloud }
            .map { it.sensorType }
            .toSet()
        val available = allTypes.filter { it.sensorType !in currentTypes }

        if (available.isEmpty()) {
            Toast.makeText(requireContext(), "所有传感器已添加", Toast.LENGTH_SHORT).show()
            return
        }

        val displayNames = available.map { "${it.icon}  ${it.name}" }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("选择要添加的传感器")
            .setItems(displayNames) { _, which ->
                vm.addSensorDevice(available[which])
                Toast.makeText(
                    requireContext(),
                    "已添加: ${available[which].name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 删除传感器确认对话框
    private fun showDeleteSensorDialog(position: Int, vm: MainViewModel) {
        val items = sensorAdapter.getItems()
        if (position !in items.indices) return
        val item = items[position]

        val suffix = if (item.isCloud) " (云平台)" else ""

        AlertDialog.Builder(requireContext())
            .setTitle("删除传感器")
            .setMessage("确定要删除 \"${item.name}\"$suffix 吗？")
            .setPositiveButton("删除") { _, _ ->
                vm.removeSensorDevice(item.id)
                Toast.makeText(
                    requireContext(),
                    "已删除: ${item.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
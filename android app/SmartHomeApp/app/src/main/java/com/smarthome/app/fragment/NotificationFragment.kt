package com.smarthome.app.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R
import com.smarthome.app.adapter.NotificationSettingAdapter
import com.smarthome.app.model.NotificationSetting
import kotlinx.coroutines.launch

class NotificationFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private lateinit var adapter: NotificationSettingAdapter
    private lateinit var recyclerView: RecyclerView
    private var notificationSettings = mutableListOf<NotificationSetting>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_notification, container, false)

        vm = (requireActivity() as MainActivity).viewModel

        requestNotifPermission()

        recyclerView = view.findViewById(R.id.recyclerNotifications)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = NotificationSettingAdapter(
            onToggle = { position, isEnabled ->
                if (position in notificationSettings.indices) {
                    notificationSettings[position] = notificationSettings[position].copy(enabled = isEnabled)
                    saveSettings()
                }
            },
            onDelete = { position ->
                if (position in notificationSettings.indices) {
                    val setting = notificationSettings[position]
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("删除通知")
                        .setMessage("确定要删除 \"${setting.sensorName}\" 的通知设置吗？")
                        .setPositiveButton("删除") { _, _ ->
                            notificationSettings.removeAt(position)
                            adapter.submitList(notificationSettings)
                            saveSettings()
                            Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            },
            onThresholdChanged = { position, high, low ->
                if (position in notificationSettings.indices) {
                    notificationSettings[position] = notificationSettings[position].copy(
                        highThreshold = high,
                        lowThreshold = low
                    )
                    saveSettings()
                }
            }
        )

        recyclerView.adapter = adapter

        // 加载设置
        loadSettings()

        // 用 post 确保在 Android 视图状态恢复之后再设置开关
        view.post { loadRuleNotifSwitch() }

        // 监听云平台传感器数据更新
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.cloudSensorValues.collect { values ->
                    // 可以在这里更新传感器实时值显示
                }
            }
        }

        // 添加通知按钮
        view.findViewById<MaterialButton>(R.id.btnAddNotification).setOnClickListener {
            showAddNotificationDialog()
        }

        // 测试通知按钮
        view.findViewById<MaterialButton>(R.id.btnTestNotif).setOnClickListener {
            vm.testNotification()
            Toast.makeText(requireContext(), "已发送测试通知", Toast.LENGTH_SHORT).show()
        }

        // 保存按钮
        view.findViewById<MaterialButton>(R.id.btnSaveNotif).setOnClickListener {
            saveSettings()
            Toast.makeText(requireContext(), "通知设置已保存", Toast.LENGTH_SHORT).show()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        loadRuleNotifSwitch()
    }


     // 添加通知对话框

    private fun showAddNotificationDialog() {
        val isCloudConnected = vm.cloudConnected.value == true

        if (!isCloudConnected) {
            Toast.makeText(requireContext(), "请先连接云平台", Toast.LENGTH_SHORT).show()
            return
        }

        val sensorItems = vm.getCloudSensorDeviceItems()
        if (sensorItems.isEmpty()) {
            Toast.makeText(requireContext(), "暂无传感器设备，请先在仪表盘添加", Toast.LENGTH_SHORT).show()
            return
        }

        // 过滤掉已经添加过的传感器
        val addedKeys = notificationSettings.map { "${it.deviceId}:${it.apiTag}" }.toSet()
        val available = sensorItems.filter { "${it.cloudDeviceId}:${it.cloudApiTag}" !in addedKeys }

        if (available.isEmpty()) {
            Toast.makeText(requireContext(), "所有传感器已添加通知", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_notification, null)
        val spSensor = dialogView.findViewById<Spinner>(R.id.spSensor)
        val etHighThreshold = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etHighThreshold)
        val etLowThreshold = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etLowThreshold)

        // 传感器列表
        val sensorNames = available.map { "${it.icon} ${it.name} (${it.cloudDeviceId})" }.toTypedArray()
        spSensor.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sensorNames)

        // 根据选择的传感器自动填充单位
        spSensor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in available.indices) {
                    val sensor = available[position]
                    // 根据传感器类型设置默认阈值
                    val tag = (sensor.cloudApiTag ?: "").lowercase()
                    when {
                        "temp" in tag -> {
                            etHighThreshold.setText("35")
                            etLowThreshold.setText("10")
                        }
                        "humi" in tag -> {
                            etHighThreshold.setText("80")
                            etLowThreshold.setText("30")
                        }
                        "light" in tag -> {
                            etHighThreshold.setText("1000")
                            etLowThreshold.setText("100")
                        }
                        "gas" in tag -> {
                            etHighThreshold.setText("400")
                            etLowThreshold.setText("")
                        }
                        else -> {
                            etHighThreshold.setText("")
                            etLowThreshold.setText("")
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加通知")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val selectedIndex = spSensor.selectedItemPosition
                if (selectedIndex !in available.indices) {
                    Toast.makeText(requireContext(), "请选择传感器", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val sensor = available[selectedIndex]
                val highText = etHighThreshold.text.toString().trim()
                val lowText = etLowThreshold.text.toString().trim()

                val highThreshold = if (highText.isEmpty()) null else highText.toFloatOrNull()
                val lowThreshold = if (lowText.isEmpty()) null else lowText.toFloatOrNull()

                if (highThreshold == null && lowThreshold == null) {
                    Toast.makeText(requireContext(), "请至少设置一个阈值", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val setting = NotificationSetting(
                    id = System.currentTimeMillis(),
                    deviceId = sensor.cloudDeviceId ?: 0,
                    deviceName = vm.getDeviceName(sensor.cloudDeviceId ?: 0),
                    apiTag = sensor.cloudApiTag ?: "",
                    sensorName = sensor.name,
                    unit = sensor.unit ?: "",
                    icon = sensor.icon,
                    highThreshold = highThreshold,
                    lowThreshold = lowThreshold,
                    enabled = true,
                    source = "cloud"
                )

                notificationSettings.add(setting)
                adapter.submitList(notificationSettings)
                saveSettings()
                Toast.makeText(requireContext(), "已添加: ${sensor.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }


     // 加载设置

    private fun loadSettings() {
        val json = vm.getNotifSettings()["notification_settings"] as? String
        if (json != null && json.isNotEmpty()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<MutableList<NotificationSetting>>() {}.type
                notificationSettings = vm.gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                notificationSettings = mutableListOf()
            }
        } else {
            // 加载旧的设置作为默认值
            migrateOldSettings()
        }
        adapter.submitList(notificationSettings)
    }


     // 迁移旧的设置

    private fun migrateOldSettings() {
        notificationSettings = mutableListOf()
        // 这里可以根据需要添加默认的通知设置
        adapter.submitList(notificationSettings)
    }


     // 保存设置

    private fun saveSettings() {
        val json = vm.gson.toJson(notificationSettings)
        vm.saveNotifSettings(mapOf("notification_settings" to json))
    }

    private fun loadRuleNotifSwitch() {
        val switchRuleNotif = view?.findViewById<SwitchMaterial>(R.id.switchRuleNotif) ?: return
        val prefs = requireContext().getSharedPreferences("smart_home_prefs", android.content.Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("notif_rule_enabled", true)
        switchRuleNotif.setOnCheckedChangeListener(null)
        switchRuleNotif.isChecked = enabled
        switchRuleNotif.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_rule_enabled", isChecked).commit()
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }
    }
}

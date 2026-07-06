package com.smarthome.app.fragment

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R
import com.smarthome.app.cloud.DeviceBaseInfo
import com.smarthome.app.cloud.NleCloudManager
import com.smarthome.app.cloud.ProjectInfo

class SettingsFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private val cloudManager: NleCloudManager get() = vm.cloudManager

    private var projectList: List<ProjectInfo> = emptyList()
    private var selectedProjectId: Int = -1

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var etCloudAccount: EditText
    private lateinit var etCloudPassword: EditText
    private lateinit var btnCloudLogin: MaterialButton
    private lateinit var tvCloudLoginStatus: TextView
    private lateinit var spProject: Spinner
    private lateinit var btnConnectCloud: MaterialButton
    private lateinit var tvCloudStatus: TextView
    private lateinit var layoutDeviceList: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        vm = (requireActivity() as MainActivity).viewModel

        initViews(view)
        initConnectionStatus()
        initWifiSection(view)
        initBluetoothSection(view)
        initCloudSection()
        restoreCloudState()

        return view
    }

    // 初始化

    private fun initViews(view: View) {
        tvStatus = view.findViewById(R.id.tvSettingsStatus)
        etCloudAccount = view.findViewById(R.id.etCloudAccount)
        etCloudPassword = view.findViewById(R.id.etCloudPassword)
        btnCloudLogin = view.findViewById(R.id.btnCloudLogin)
        tvCloudLoginStatus = view.findViewById(R.id.tvCloudLoginStatus)
        spProject = view.findViewById(R.id.spProject)
        btnConnectCloud = view.findViewById(R.id.btnConnectCloud)
        tvCloudStatus = view.findViewById(R.id.tvCloudStatus)
        layoutDeviceList = view.findViewById(R.id.layoutDeviceList)
    }

    private fun initConnectionStatus() {
        vm.connectionStatus.observe(viewLifecycleOwner) { status ->
            tvStatus.text = status
            tvStatus.setTextColor(
                if (status.contains("已连接") || status.contains("成功") || status.contains("实时"))
                    0xFF4CAF50.toInt() else 0xFFF44336.toInt()
            )
        }
    }

    // 恢复状态

    private fun restoreCloudState() {
        val (savedAccount, savedPassword) = cloudManager.getSavedCredentials()
        if (savedAccount.isNotBlank()) {
            etCloudAccount.setText(savedAccount)
            etCloudPassword.setText(savedPassword)
        }

        val isLoggedIn = vm.cloudLoginState.value == true
        val isConnected = vm.cloudConnected.value == true

        if (isLoggedIn) {
            applyLoggedInUI()

            val savedList = vm.getSavedProjectList()
            if (savedList.size > 0) {
                projectList = savedList
                fillProjectSpinner()
                val idx = projectList.indexOfFirst { it.ProjectID == vm.getSavedProjectId() }
                if (idx >= 0) spProject.setSelection(idx)
            } else {
                cloudManager.getProjects { projects ->
                    activity?.runOnUiThread {
                        projectList = projects
                        vm.saveProjectList(projects)
                        fillProjectSpinner()
                    }
                }
            }

            if (isConnected) {
                applyConnectedUI()
                val devices = vm.getSavedDeviceList()
                if (devices.size > 0) showDeviceList(devices)
            } else {
                applyDisconnectedUI()
            }
        }
    }

    // WiFi

    private fun initWifiSection(view: View) {
        val etHost = view.findViewById<EditText>(R.id.etHost)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        view.findViewById<MaterialButton>(R.id.btnConnectWifi).setOnClickListener {
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull() ?: 8080
            if (host.isBlank()) {
                Toast.makeText(requireContext(), "请输入 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.connectWifi(host, port)
        }
    }

    // 蓝牙

    private fun initBluetoothSection(view: View) {
        view.findViewById<MaterialButton>(R.id.btnConnectBt).setOnClickListener {
            showBluetoothDialog()
        }
    }

    // 云平台

    private fun initCloudSection() {

        // 登录 / 退出登录
        btnCloudLogin.setOnClickListener {
            if (vm.cloudLoginState.value == true) {
                showLogoutConfirmDialog()
            } else {
                doLogin()
            }
        }

        // 连接云平台 / 断开连接
        btnConnectCloud.setOnClickListener {
            if (vm.cloudConnected.value == true) {

                showCloudDisconnectDialog()
            } else {

                doConnectCloud()
            }
        }

        view?.findViewById<MaterialButton>(R.id.btnDisconnect)?.setOnClickListener {
            vm.disconnect()
            Toast.makeText(requireContext(), "本地连接已断开", Toast.LENGTH_SHORT).show()
        }
    }

    // 登录

    private fun doLogin() {
        val account = etCloudAccount.text.toString().trim()
        val password = etCloudPassword.text.toString().trim()

        if (account.isBlank() || password.isBlank()) {
            Toast.makeText(requireContext(), "请输入账号和密码", Toast.LENGTH_SHORT).show()
            return
        }

        btnCloudLogin.isEnabled = false
        btnCloudLogin.text = "登录中..."
        tvCloudLoginStatus.text = "正在登录..."

        cloudManager.login(account, password) { success, msg ->
            activity?.runOnUiThread {
                if (success) {
                    tvCloudLoginStatus.text = "登录成功，正在获取项目..."

                    cloudManager.getProjects { projects ->
                        activity?.runOnUiThread {
                            projectList = projects
                            vm.saveProjectList(projects)

                            if (projects.size > 0) {
                                fillProjectSpinner()
                                applyLoggedInUI()
                            } else {
                                btnCloudLogin.isEnabled = true
                                btnCloudLogin.text = "登录云平台"
                                tvCloudLoginStatus.text = "登录成功，但未找到项目"
                                tvCloudLoginStatus.setTextColor(0xFFFF9800.toInt())
                            }
                        }
                    }
                } else {
                    btnCloudLogin.isEnabled = true
                    btnCloudLogin.text = "登录云平台"
                    tvCloudLoginStatus.text = "登录失败: $msg"
                    tvCloudLoginStatus.setTextColor(0xFFF44336.toInt())
                }
            }
        }
    }

    // 退出登录

    private fun showLogoutConfirmDialog() {
        val isConnected = vm.cloudConnected.value == true
        val message = if (isConnected) {
            "当前云平台已连接，退出登录将同时断开连接。确定退出吗？"
        } else {
            "确定退出登录吗？"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("退出登录")
            .setMessage(message)
            .setPositiveButton("退出") { _, _ ->
                doLogout()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doLogout() {
        vm.logoutCloud()
        applyLoggedOutUI()
        Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
    }

    // 连接云平台

    private fun doConnectCloud() {
        val pos = spProject.selectedItemPosition
        if (pos !in projectList.indices) {
            Toast.makeText(requireContext(), "请先选择项目", Toast.LENGTH_SHORT).show()
            return
        }

        val project = projectList[pos]
        selectedProjectId = project.ProjectID
        vm.saveProjectName(project.Name)

        btnConnectCloud.isEnabled = false
        btnConnectCloud.text = "连接中..."
        tvCloudStatus.text = "正在连接项目: ${project.Name}..."
        layoutDeviceList.removeAllViews()
        layoutDeviceList.visibility = View.GONE

        cloudManager.connectToProject(project.ProjectID) { success ->
            activity?.runOnUiThread {
                if (success) {
                    vm.connectCloud(cloudManager)
                    applyConnectedUI()
                    loadDevices(project.ProjectID)
                } else {
                    btnConnectCloud.isEnabled = true
                    btnConnectCloud.text = "连接云平台"
                    tvCloudStatus.text = "连接失败，请重试"
                    tvCloudStatus.setTextColor(0xFFF44336.toInt())
                }
            }
        }
    }

    // 断开云平台连接

    private fun showCloudDisconnectDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("断开云平台")
            .setMessage("确定断开云平台连接吗？")
            .setPositiveButton("断开") { _, _ ->
                doCloudDisconnect()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doCloudDisconnect() {
        vm.disconnectCloudOnly()
        applyDisconnectedUI()
        Toast.makeText(requireContext(), "云平台已断开", Toast.LENGTH_SHORT).show()
    }


    private fun applyLoggedInUI() {
        btnCloudLogin.text = "退出登录"
        btnCloudLogin.isEnabled = true
        tvCloudLoginStatus.text = "已登录"
        tvCloudLoginStatus.setTextColor(0xFF4CAF50.toInt())
        etCloudAccount.isEnabled = false
        etCloudPassword.isEnabled = false
        spProject.isEnabled = true
        btnConnectCloud.isEnabled = true
        btnConnectCloud.text = "连接云平台"
    }


    private fun applyLoggedOutUI() {
        btnCloudLogin.text = "登录云平台"
        btnCloudLogin.isEnabled = true
        tvCloudLoginStatus.text = "未登录"
        tvCloudLoginStatus.setTextColor(0xFF757575.toInt())
        etCloudAccount.isEnabled = true
        etCloudPassword.isEnabled = true

        spProject.adapter = null
        spProject.isEnabled = false
        projectList = emptyList()

        btnConnectCloud.text = "连接云平台"
        btnConnectCloud.isEnabled = false

        tvCloudStatus.text = ""
        layoutDeviceList.removeAllViews()
        layoutDeviceList.visibility = View.GONE
    }


    private fun applyConnectedUI() {
        val name = vm.getSavedProjectName()
        btnConnectCloud.text = "断开连接"
        btnConnectCloud.isEnabled = true
        spProject.isEnabled = false
        tvCloudStatus.text = "已连接项目: $name"
        tvCloudStatus.setTextColor(0xFF4CAF50.toInt())
    }


    private fun applyDisconnectedUI() {
        btnConnectCloud.text = "连接云平台"
        btnConnectCloud.isEnabled = true
        spProject.isEnabled = true
        tvCloudStatus.text = ""
        layoutDeviceList.removeAllViews()
        layoutDeviceList.visibility = View.GONE
    }

    // 获取设备

    private fun loadDevices(projectId: Int) {
        tvCloudStatus.text = "正在获取设备列表..."

        cloudManager.getDevicesByProject(projectId) { devices ->
            activity?.runOnUiThread {
                if (devices.size > 0) {
                    tvCloudStatus.text = "已连接，共 ${devices.size} 个设备"
                    showDeviceList(devices)
                    vm.setupCloudDevices(devices, projectId)
                } else {
                    tvCloudStatus.text = "已连接，该项目下无设备"
                }
            }
        }
    }

    // 设备列表展示

    private fun showDeviceList(devices: List<DeviceBaseInfo>) {
        if (!isAdded || context == null) return

        layoutDeviceList.removeAllViews()
        layoutDeviceList.visibility = View.VISIBLE

        val titleView = TextView(requireContext()).apply {
            text = "项目设备"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF424242.toInt())
            setPadding(0, 0, 0, 8)
        }
        layoutDeviceList.addView(titleView)

        for (device in devices) {
            val itemView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(16, 16).apply { marginEnd = 12 }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (device.IsOnline) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
                }
            }
            itemView.addView(dot)

            val infoView = TextView(requireContext()).apply {
                text = "${device.Name}  (ID: ${device.DeviceID})"
                textSize = 13f
                setTextColor(0xFF616161.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            itemView.addView(infoView)

            val statusView = TextView(requireContext()).apply {
                text = if (device.IsOnline) "在线" else "离线"
                textSize = 12f
                setTextColor(
                    if (device.IsOnline) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
                )
            }
            itemView.addView(statusView)

            layoutDeviceList.addView(itemView)
        }
    }

    private fun fillProjectSpinner() {
        val names = projectList.map { "${it.Name} (ID:${it.ProjectID})" }
        spProject.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            names
        )
    }

    // 蓝牙对话框

    private fun showBluetoothDialog() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(requireContext(), "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(requireContext(), "请先打开蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 3001)
                return
            }
        }

        val pairedDevices = adapter.bondedDevices.toList()
        if (pairedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "没有已配对设备", Toast.LENGTH_SHORT).show()
            return
        }

        val names = pairedDevices.map { "${it.name ?: "未知"}\n${it.address}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择蓝牙设备")
            .setItems(names) { _, which -> vm.connectBluetooth(pairedDevices[which]) }
            .setNegativeButton("取消", null)
            .show()
    }
}
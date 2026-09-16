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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.launch
import com.smarthome.app.R
import com.smarthome.app.cloud.DeviceBaseInfo
import com.smarthome.app.cloud.ProjectInfo
import com.smarthome.app.cloud.ServiceEventBus
import com.smarthome.app.viewmodel.SettingsViewModel
import com.smarthome.app.util.UpdateChecker
import java.io.IOException
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private val settingsVm: SettingsViewModel by viewModels()

    private var projectList: List<ProjectInfo> = emptyList()
    private var selectedProjectId: Int = -1

    // UI
    private lateinit var etCloudAccount: EditText
    private lateinit var etCloudPassword: EditText
    private lateinit var btnCloudLogin: MaterialButton
    private lateinit var tvCloudLoginStatus: TextView
    private lateinit var spProject: Spinner
    private lateinit var btnConnectCloud: MaterialButton
    private lateinit var tvCloudStatus: TextView
    private lateinit var layoutDeviceList: LinearLayout
    private lateinit var btnCreateProject: MaterialButton
    private lateinit var btnDeleteProject: MaterialButton
    private lateinit var btnCreateDevice: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        vm = (requireActivity() as MainActivity).viewModel

        initViews(view)
        initConnectionStatus()
        initVersionCheck(view)
        initWifiSection(view)
        initBluetoothSection(view)
        initCloudSection(view)
        restoreCloudState()

        return view
    }

    // 初始化

    private fun initViews(view: View) {
        etCloudAccount = view.findViewById(R.id.etCloudAccount)
        etCloudPassword = view.findViewById(R.id.etCloudPassword)
        btnCloudLogin = view.findViewById(R.id.btnCloudLogin)
        tvCloudLoginStatus = view.findViewById(R.id.tvCloudLoginStatus)
        spProject = view.findViewById(R.id.spProject)
        btnConnectCloud = view.findViewById(R.id.btnConnectCloud)
        tvCloudStatus = view.findViewById(R.id.tvCloudStatus)
        layoutDeviceList = view.findViewById(R.id.layoutDeviceList)
        btnCreateProject = view.findViewById(R.id.btnCreateProject)
        btnDeleteProject = view.findViewById(R.id.btnDeleteProject)
        btnCreateDevice = view.findViewById(R.id.btnCreateDevice)
    }

    private fun initConnectionStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.cloudConnected.collect { connected ->
                    if (connected && vm.getSavedProjectId() > 0) {
                        applyConnectedUI()
                        loadDevices(vm.getSavedProjectId())
                    } else if (!connected) {
                        applyDisconnectedUI()
                    }
                }
            }
        }
        // 监听设备在线状态变化，自动刷新设备列表显示
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceEventBus.deviceOnlineStatus.collect { statusMap ->
                    if (vm.cloudConnected.value == true) {
                        val devices = vm.getSavedDeviceList()
                        if (devices.isNotEmpty()) {
                            showDeviceList(devices)
                        }
                    }
                }
            }
        }
    }

    private var tvVersionRef: TextView? = null
    private var currentAppVersion: String = "1.0.0"

    private fun initVersionCheck(view: View) {
        val tvVersion = view.findViewById<TextView>(R.id.tvVersion) ?: return
        val pkgInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        currentAppVersion = pkgInfo.versionName ?: "1.0.0"
        tvVersion.text = "版本$currentAppVersion"
        tvVersionRef = tvVersion

        tvVersion.setOnLongClickListener {
            checkForUpdate(currentAppVersion, tvVersion)
            true
        }

        UpdateChecker.onUpdateChecked = { _ ->
            activity?.runOnUiThread { applyVersionColor() }
        }

        applyVersionColor()
    }

    override fun onResume() {
        super.onResume()
        applyVersionColor()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        UpdateChecker.onUpdateChecked = null
        tvVersionRef = null
    }

    private fun applyVersionColor() {
        val tv = tvVersionRef ?: return
        if (UpdateChecker.hasUpdate) {
            tv.setTextColor(0xFFF44336.toInt())
        } else {
            tv.setTextColor(0xFF757575.toInt())
        }
    }

    private fun checkForUpdate(currentVersion: String, tvVersion: TextView? = null) {
        Toast.makeText(requireContext(), "正在检查更新...", Toast.LENGTH_SHORT).show()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("https://api.github.com/repos/xiao-dgLI/Smart-Home-Project/releases/latest")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("检查失败")
                        .setMessage("无法连接到服务器，请检查网络后重试。\n\n${e.message}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string() ?: return
                    activity?.runOnUiThread {
                        try {
                            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                            val tagName = json.get("tag_name")?.asString ?: ""
                            val latestVersion = tagName.removePrefix("v")
                            val releaseName = json.get("name")?.asString ?: tagName
                            val description = json.get("body")?.asString ?: ""
                            val htmlUrl = json.get("html_url")?.asString ?: ""
                            val publishedAt = json.get("published_at")?.asString ?: ""

                            var apkUrl = ""
                            val assets = json.getAsJsonArray("assets")
                            if (assets != null) {
                                for (asset in assets) {
                                    val name = asset.asJsonObject.get("name")?.asString ?: ""
                                    if (name.endsWith(".apk")) {
                                        apkUrl = asset.asJsonObject.get("browser_download_url")?.asString ?: ""
                                        break
                                    }
                                }
                            }

                            val hasUpdate = isVersionNewer(latestVersion, currentVersion)
                            UpdateChecker.hasUpdate = hasUpdate
                            UpdateChecker.latestVersionInfo = latestVersion
                            val dateStr = if (publishedAt.isNotBlank()) publishedAt.substring(0, 10) else ""

                            val message = buildString {
                                append("当前版本: v$currentVersion\n")
                                append("最新版本: v$latestVersion\n")
                                if (dateStr.isNotBlank()) append("发布时间: $dateStr\n")
                                append("\n$releaseName")
                                if (description.isNotBlank()) append("\n$description")
                            }

                            if (hasUpdate) {
                                tvVersion?.setTextColor(0xFFF44336.toInt())
                                val dialog = MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("发现新版本!")
                                    .setMessage(message)
                                    .setPositiveButton("下载更新") { _, _ ->
                                        val url = if (apkUrl.isNotBlank()) apkUrl else htmlUrl
                                        if (url.isNotBlank()) {
                                            startActivity(
                                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            )
                                        }
                                    }
                                    .setNegativeButton("稍后", null)

                                if (htmlUrl.isNotBlank()) {
                                    dialog.setNeutralButton("查看详情") { _, _ ->
                                        startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(htmlUrl))
                                        )
                                    }
                                }
                                dialog.show()
                            } else {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("版本信息")
                                    .setMessage(message + "\n\n当前已是最新版本")
                                    .setPositiveButton("确定", null)
                                    .show()
                            }
                        } catch (e: Exception) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("解析失败")
                                .setMessage("无法解析版本信息，请稍后重试。\n\n${e.message}")
                                .setPositiveButton("确定", null)
                                .show()
                        }
                    }
                }
            }
        })
    }


     // 比较版本号：newVersion > currentVersion 返回 true

    private fun isVersionNewer(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val curParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(newParts.size, curParts.size)
        for (i in 0 until maxLen) {
            val n = newParts.getOrElse(i) { 0 }
            val c = curParts.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }

    // 恢复状态

    private fun restoreCloudState() {
        val (savedAccount, savedPassword) = settingsVm.getSavedCredentials()
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
                lifecycleScope.launch {
                    val projects = settingsVm.getProjects()
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
            settingsVm.connectWifi(vm, host, port)
        }
    }

    // 蓝牙

    private fun initBluetoothSection(view: View) {
        view.findViewById<MaterialButton>(R.id.btnConnectBt).setOnClickListener {
            showBluetoothDialog()
        }
    }

    // 云平台

    private fun initCloudSection(view: View) {

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

        // 创建新项目
        view.findViewById<MaterialButton>(R.id.btnCreateProject)?.setOnClickListener {
            showCreateProjectDialog()
        }

        // 删除项目
        view.findViewById<MaterialButton>(R.id.btnDeleteProject)?.setOnClickListener {
            val pos = spProject.selectedItemPosition
            if (pos !in projectList.indices) {
                Toast.makeText(requireContext(), "请先选择要删除的项目", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val project = projectList[pos]
            val isCurrentProject = vm.cloudConnected.value == true
                    && vm.getSavedProjectId() == project.ProjectID

            if (isCurrentProject) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("无法删除")
                    .setMessage("项目「${project.Name}」当前正在连接中，请先断开连接后再删除。")
                    .setPositiveButton("去断开", null)
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除项目")
                .setMessage("确定要删除项目「${project.Name}」(ID: ${project.ProjectID}) 吗？\n\n此操作不可恢复，项目下的所有设备和传感器数据将被删除。")
                .setPositiveButton("删除") { _, _ ->
                    Toast.makeText(requireContext(), "正在删除...", Toast.LENGTH_SHORT).show()
                    settingsVm.deleteProject(vm, project.ProjectID) { success ->
                        activity?.runOnUiThread {
                            if (success) {
                                Toast.makeText(requireContext(), "项目已删除", Toast.LENGTH_SHORT).show()
                                refreshProjectList()
                            } else {
                                Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 创建设备
        btnCreateDevice.setOnClickListener {
            showCreateDeviceDialog()
        }

        view.findViewById<MaterialButton>(R.id.btnDisconnect)?.setOnClickListener {
            settingsVm.disconnect(vm)
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

        settingsVm.login(account, password) { success, msg ->
            activity?.runOnUiThread {
                if (success) {
                    tvCloudLoginStatus.text = "登录成功，正在获取项目..."

                    lifecycleScope.launch {
                        val projects = settingsVm.getProjects()
                        activity?.runOnUiThread {
                            projectList = projects
                            vm.saveProjectList(projects)

                            fillProjectSpinner()
                            applyLoggedInUI()

                            if (projects.isEmpty()) {
                                tvCloudLoginStatus.text = "登录成功，未找到项目，请创建"
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
        settingsVm.logout(vm)
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

        btnConnectCloud.isEnabled = false
        btnConnectCloud.text = "连接中..."
        tvCloudStatus.text = "正在连接项目: ${project.Name}..."
        layoutDeviceList.removeAllViews()
        layoutDeviceList.visibility = View.GONE

        settingsVm.connectToProject(vm, project.ProjectID, project.Name) { success ->
            activity?.runOnUiThread {
                if (success) {
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
        settingsVm.disconnectCloud(vm)
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
        btnCreateDevice.visibility = View.GONE
    }

    // 获取设备

    private fun loadDevices(projectId: Int) {
        tvCloudStatus.text = "正在获取设备列表..."

        lifecycleScope.launch {
            val devices = settingsVm.getDevices(projectId)
            activity?.runOnUiThread {
                if (devices.size > 0) {
                    tvCloudStatus.text = "已连接，共 ${devices.size} 个设备"
                    showDeviceList(devices)
                } else {
                    tvCloudStatus.text = "已连接，该项目下无设备"
                    layoutDeviceList.removeAllViews()
                    layoutDeviceList.visibility = View.GONE
                    btnCreateDevice.visibility = View.VISIBLE
                }
                // 无论设备列表是否为空，都设置云平台设备并启动轮询
                vm.setupCloudDevices(devices, projectId)
            }
        }
    }

    // 设备列表展示

    private fun showDeviceList(devices: List<DeviceBaseInfo>) {
        if (!isAdded || context == null) return

        layoutDeviceList.removeAllViews()
        layoutDeviceList.visibility = View.VISIBLE
        btnCreateDevice.visibility = View.VISIBLE

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
                setPadding(0, 10, 0, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
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
                textSize = 16f
                setTextColor(0xFF424242.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            itemView.addView(infoView)

            val statusView = TextView(requireContext()).apply {
                text = if (device.IsOnline) "在线" else "离线"
                textSize = 14f
                setTextColor(
                    if (device.IsOnline) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
                )
                setPadding(0, 0, 8, 0)
            }
            itemView.addView(statusView)

            val moreBtnContainer = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    80,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    showDeviceDetailDialog(device)
                }
            }
            val moreBtn = TextView(requireContext()).apply {
                text = "···"
                textSize = 20f
                setTextColor(0xFF616161.toInt())
                translationY = -4f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
            }
            moreBtnContainer.addView(moreBtn)
            itemView.addView(moreBtnContainer)

            layoutDeviceList.addView(itemView)
        }
    }

    private fun showDeviceDetailDialog(device: DeviceBaseInfo) {
        val protocolName = when (device.Protocol) {
            1 -> "TCP"
            2 -> "MQTT"
            3 -> "HTTP"
            else -> "未知 (${device.Protocol})"
        }

        val fields = listOf(
            "设备名称" to device.Name,
            "设备ID" to device.DeviceID.toString(),
            "设备标识" to device.SerialNumber,
            "传输密钥" to device.SecretKey,
            "通讯协议" to protocolName,
            "在线状态" to if (device.IsOnline) "在线" else "离线"
        )

        val scrollView = ScrollView(requireContext()).apply {
            setPadding(48, 24, 48, 0)
        }

        val linearLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        fields.forEach { (label, value) ->
            val itemLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 16)
            }

            val labelView = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                setTextColor(resources.getColor(R.color.primary, null))
            }

            val valueView = TextView(requireContext()).apply {
                text = value
                textSize = 16f
                isClickable = true
                isFocusable = true
                setOnLongClickListener {
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText(label, value)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), "已复制: $label", Toast.LENGTH_SHORT).show()
                    true
                }
                setBackgroundResource(android.R.drawable.list_selector_background)
            }

            itemLayout.addView(labelView)
            itemLayout.addView(valueView)
            linearLayout.addView(itemLayout)
        }

        scrollView.addView(linearLayout)

        lateinit var detailDialog: AlertDialog
        detailDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("设备详情")
            .setView(scrollView)
            .setPositiveButton("确定", null)
            .setNeutralButton("删除设备") { _, _ ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("删除设备")
                    .setMessage("确定要删除设备「${device.Name}」(ID: ${device.DeviceID}) 吗？\n\n此操作不可恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        Toast.makeText(requireContext(), "正在删除...", Toast.LENGTH_SHORT).show()
                        settingsVm.deleteDevice(vm, device.DeviceID) { success ->
                            activity?.runOnUiThread {
                                if (success) {
                                    detailDialog.dismiss()
                                    Toast.makeText(requireContext(), "设备已删除", Toast.LENGTH_SHORT).show()
                                    loadDevices(vm.getSavedProjectId())
                                } else {
                                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .show()
    }

    private fun fillProjectSpinner() {
        if (projectList.isEmpty()) {
            spProject.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("暂无项目，请创建")
            )
            spProject.isEnabled = false
            btnCreateProject.visibility = View.VISIBLE
            btnDeleteProject.visibility = View.GONE
            btnCreateDevice.visibility = View.GONE
            return
        }

        val names = projectList.map { "${it.Name} (ID:${it.ProjectID})" }
        spProject.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            names
        )
        spProject.isEnabled = true
        btnCreateProject.visibility = View.VISIBLE
        btnDeleteProject.visibility = View.VISIBLE
    }

    private fun showCreateProjectDialog() {
        val input = EditText(requireContext()).apply {
            hint = "项目名称（1-15个字符）"
            setPadding(60, 40, 60, 40)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("创建新项目")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "请输入项目名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.length > 15) {
                    Toast.makeText(requireContext(), "名称不能超过15个字符", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                settingsVm.createProject(vm, name) { success, msg ->
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        if (success) refreshProjectList()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshProjectList() {
        lifecycleScope.launch {
            val projects = settingsVm.getProjects()
            activity?.runOnUiThread {
                projectList = projects
                vm.saveProjectList(projects)
                fillProjectSpinner()
                val idx = projectList.indexOfFirst { it.ProjectID == vm.getSavedProjectId() }
                if (idx >= 0) spProject.setSelection(idx)
            }
        }
    }

    private fun showCreateDeviceDialog() {
        val pos = spProject.selectedItemPosition
        if (pos !in projectList.indices) {
            Toast.makeText(requireContext(), "请先选择项目", Toast.LENGTH_SHORT).show()
            return
        }
        val project = projectList[pos]

        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val nameInput = EditText(requireContext()).apply {
            hint = "设备名称（1-15个字符）"
            setPadding(60, 40, 60, 40)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val tagInput = EditText(requireContext()).apply {
            hint = "设备标识（英文数字下划线，6-30位）"
            setPadding(60, 20, 60, 40)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val spProtocol = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                arrayOf("TCP", "MQTT", "HTTP"))
            setSelection(0)
            setPadding(60, 20, 60, 20)
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
            addView(nameInput)
            addView(tagInput)

            val protocolLabel = TextView(requireContext()).apply {
                text = "通信协议"
                setPadding(0, 20, 0, 4)
            }
            addView(protocolLabel)
            addView(spProtocol)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("创建新设备")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = nameInput.text.toString().trim()
                val tag = tagInput.text.toString().trim()
                val protocol = spProtocol.selectedItemPosition + 1

                if (name.isBlank() || name.length > 15) {
                    Toast.makeText(requireContext(), "名称需1-15个字符", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (tag.isBlank() || tag.length < 6 || tag.length > 30 || !tag.matches(Regex("^[a-zA-Z0-9_]+$"))) {
                    Toast.makeText(requireContext(), "标识需6-30位英文数字下划线", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                Toast.makeText(requireContext(), "正在创建设备...", Toast.LENGTH_SHORT).show()
                settingsVm.createDevice(vm, project.ProjectID, name, tag, protocol) { success, msg ->
                    activity?.runOnUiThread {
                        if (success) {
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            loadDevices(project.ProjectID)
                        } else {
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            .setItems(names) { _, which -> settingsVm.connectBluetooth(vm, pairedDevices[which]) }
            .setNegativeButton("取消", null)
            .show()
    }
}
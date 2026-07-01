package com.smarthome.app.fragment

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R

class SettingsFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private val REQUEST_BT = 1001

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val vm = (requireActivity() as MainActivity).viewModel

        val etHost = view.findViewById<TextInputEditText>(R.id.etHost)
        val etPort = view.findViewById<TextInputEditText>(R.id.etPort)
        val tvStatus = view.findViewById<TextView>(R.id.tvSettingsStatus)

        val prefs = requireActivity().getSharedPreferences("smart_home_prefs", 0)
        etHost.setText(prefs.getString("wifi_host", "192.168.1.100"))
        etPort.setText(prefs.getInt("wifi_port", 8080).toString())

        view.findViewById<MaterialButton>(R.id.btnConnectWifi).setOnClickListener {
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull() ?: 8080
            if (host.isBlank()) {
                Toast.makeText(requireContext(), "请输入服务器IP地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.connectWifi(host, port)
        }

        view.findViewById<MaterialButton>(R.id.btnConnectBt).setOnClickListener {
            requestBtAndConnect()
        }

        view.findViewById<MaterialButton>(R.id.btnDisconnect).setOnClickListener {
            vm.disconnect()
            tvStatus.text = "已断开"
        }

        vm.connectionStatus.observe(viewLifecycleOwner) { status ->
            tvStatus.text = status
        }

        return view
    }

    private fun requestBtAndConnect() {
        val perms = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val needed = perms.filter {
            ActivityCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_BT)
            return
        }
        showBtList()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            showBtList()
        }
    }

    private fun showBtList() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(requireContext(), "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(requireContext(), "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        if (ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val paired = adapter.bondedDevices
        if (paired.isEmpty()) {
            Toast.makeText(requireContext(), "没有已配对的蓝牙设备", Toast.LENGTH_SHORT).show()
            return
        }

        val names = paired.map { "${it.name ?: "未知"}\n${it.address}" }.toTypedArray()
        val list = paired.toList()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择蓝牙设备")
            .setItems(names) { _, i -> vm.connectBluetooth(list[i]) }
            .setNegativeButton("取消", null)
            .show()
    }
}
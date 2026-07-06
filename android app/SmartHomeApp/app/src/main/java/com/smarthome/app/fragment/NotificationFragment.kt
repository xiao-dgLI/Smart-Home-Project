package com.smarthome.app.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R

class NotificationFragment : Fragment() {

    private lateinit var vm: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_notification, container, false)

        vm = (requireActivity() as MainActivity).viewModel

        requestNotifPermission()

        val switchGlobal = view.findViewById<Switch>(R.id.switchNotifGlobal)
        val switchTemp = view.findViewById<Switch>(R.id.switchTempNotif)
        val etTempHigh = view.findViewById<EditText>(R.id.etTempHigh)
        val etTempLow = view.findViewById<EditText>(R.id.etTempLow)
        val switchHumi = view.findViewById<Switch>(R.id.switchHumiNotif)
        val etHumiHigh = view.findViewById<EditText>(R.id.etHumiHigh)
        val etHumiLow = view.findViewById<EditText>(R.id.etHumiLow)
        val switchLight = view.findViewById<Switch>(R.id.switchLightNotif)
        val etLightHigh = view.findViewById<EditText>(R.id.etLightHigh)
        val etLightLow = view.findViewById<EditText>(R.id.etLightLow)
        val switchPir = view.findViewById<Switch>(R.id.switchPirNotif)
        val switchRule = view.findViewById<Switch>(R.id.switchRuleNotif)
        val btnTest = view.findViewById<MaterialButton>(R.id.btnTestNotif)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveNotif)
        val switchFlame = view.findViewById<Switch>(R.id.switchFlameNotif)
        val switchGas = view.findViewById<Switch>(R.id.switchGasNotif)
        val etGasThreshold = view.findViewById<EditText>(R.id.etGasThreshold)

        val settings = vm.getNotifSettings()

        switchGlobal.isChecked = settings["notif_enabled"] as Boolean
        switchTemp.isChecked = settings["notif_temp_enabled"] as Boolean
        switchHumi.isChecked = settings["notif_humi_enabled"] as Boolean
        switchLight.isChecked = settings["notif_light_enabled"] as Boolean
        switchPir.isChecked = settings["notif_pir_enabled"] as Boolean
        switchRule.isChecked = settings["notif_rule_enabled"] as Boolean
        switchFlame.isChecked = settings["notif_flame_enabled"] as Boolean
        switchGas.isChecked = settings["notif_gas_enabled"] as Boolean
        loadThreshold(etGasThreshold, settings["notif_gas_threshold"])


        loadThreshold(etTempHigh, settings["notif_temp_high"])
        loadThreshold(etTempLow, settings["notif_temp_low"])
        loadThreshold(etHumiHigh, settings["notif_humi_high"])
        loadThreshold(etHumiLow, settings["notif_humi_low"])
        loadThreshold(etLightHigh, settings["notif_light_high"])
        loadThreshold(etLightLow, settings["notif_light_low"])

        // 测试通知
        btnTest.setOnClickListener {
            vm.testNotification()
            Toast.makeText(requireContext(), "已发送测试通知", Toast.LENGTH_SHORT).show()
        }

        // 保存
        btnSave.setOnClickListener {
            try {
                val map = mapOf<String, Any>(
                    "notif_enabled" to switchGlobal.isChecked,
                    "notif_temp_enabled" to switchTemp.isChecked,
                    "notif_temp_high" to parseThresholdFloat(etTempHigh),
                    "notif_temp_low" to parseThresholdFloat(etTempLow),
                    "notif_humi_enabled" to switchHumi.isChecked,
                    "notif_humi_high" to parseThresholdFloat(etHumiHigh),
                    "notif_humi_low" to parseThresholdFloat(etHumiLow),
                    "notif_light_enabled" to switchLight.isChecked,
                    "notif_light_high" to parseThresholdInt(etLightHigh),
                    "notif_light_low" to parseThresholdInt(etLightLow),
                    "notif_pir_enabled" to switchPir.isChecked,
                    "notif_rule_enabled" to switchRule.isChecked,
                    "notif_flame_enabled" to switchFlame.isChecked,
                    "notif_gas_enabled" to switchGas.isChecked,
                    "notif_gas_threshold" to parseThresholdInt(etGasThreshold)
                )
                vm.saveNotifSettings(map)
                Toast.makeText(requireContext(), "通知设置已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }

    // 加载阈值到输入框
    private fun loadThreshold(et: EditText, value: Any?) {
        try {
            when (value) {
                is Float -> {
                    if (value.isNaN() || value == -999f) {
                        et.setText("")
                    } else {
                        et.setText(value.toInt().toString())
                    }
                }
                is Int -> {
                    if (value == -999) {
                        et.setText("")
                    } else {
                        et.setText(value.toString())
                    }
                }
                else -> et.setText("")
            }
        } catch (_: Exception) {
            et.setText("")
        }
    }

    private fun parseThresholdFloat(et: EditText): Float {
        val text = et.text.toString().trim()
        return if (text.isEmpty()) Float.NaN else text.toFloatOrNull() ?: Float.NaN
    }

    private fun parseThresholdInt(et: EditText): Int {
        val text = et.text.toString().trim()
        return if (text.isEmpty()) -999 else text.toIntOrNull() ?: -999
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
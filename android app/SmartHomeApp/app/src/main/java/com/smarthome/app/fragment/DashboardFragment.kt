package com.smarthome.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.smarthome.app.MainActivity
import com.smarthome.app.databinding.FragmentDashboardBinding

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

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

        // 传感器数据
        vm.sensorData.observe(viewLifecycleOwner) { data ->
            binding.tvTemp.text = String.format("%.1f °C", data.temp)
            binding.tvHumi.text = String.format("%.1f %%RH", data.humi)
            binding.tvLight.text = "${data.light} lux"
            binding.tvPir.text = if (data.pir == 1) "有人" else "无人"
            binding.tvPirState.text = if (data.pir == 1) "●" else "○"

            binding.tvTemp.setTextColor(
                when {
                    data.temp > 35 -> 0xFFFF4444.toInt()
                    data.temp > 28 -> 0xFFFF8800.toInt()
                    else -> 0xFF4CAF50.toInt()
                }
            )

            binding.tvLight.setTextColor(
                if (data.light > 500) 0xFFFFC107.toInt() else 0xFF607D8B.toInt()
            )
        }

        // 连接状态
        vm.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.tvConnectionStatus.text = status
        }

        // 日志（自动滚动到顶部）
        vm.logMessages.observe(viewLifecycleOwner) { logs ->
            binding.tvLogs.text = logs.take(50).joinToString("\n")
            binding.scrollLogs.post {
                binding.scrollLogs.fullScroll(View.FOCUS_UP)
            }
        }

        // 发送指令按钮
        binding.btnSendCmd.setOnClickListener {
            val cmd = binding.etSendCmd.text.toString().trim()
            if (cmd.isBlank()) {
                Toast.makeText(requireContext(), "请输入指令", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.sendRawCmd(cmd)
            binding.etSendCmd.text.clear()
        }

        // 清空日志按钮
        binding.btnClearLogs.setOnClickListener {
            vm.clearLogs()
            Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
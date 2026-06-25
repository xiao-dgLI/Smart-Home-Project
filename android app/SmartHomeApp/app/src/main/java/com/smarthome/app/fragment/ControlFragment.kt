package com.smarthome.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.smarthome.app.MainActivity
import com.smarthome.app.MainViewModel
import com.smarthome.app.R
import com.smarthome.app.adapter.ControlAdapter
import com.smarthome.app.model.ControlSwitch

class ControlFragment : Fragment() {

    private lateinit var vm: MainViewModel
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
                vm.toggleSwitchSilent(index)
            },
            onEdit = { index -> showEditNameDialog(index) },
            onEditOnCmd = { index -> showEditCmdDialog(index, true) },
            onEditOffCmd = { index -> showEditCmdDialog(index, false) },
            onDelete = { index -> showDeleteConfirm(index) },
            onCorrect = { index -> showCorrectDialog(index) }
        )
        recyclerView.adapter = adapter

        vm.switches.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list.toList())
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddSwitch).setOnClickListener {
            showAddSwitchDialog()
        }

        return view
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
                vm.addSwitch(sw)
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
                vm.updateSwitch(index, sw)
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
                    vm.updateSwitch(index, sw)
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
                vm.correctSwitchState(index)
                // 带动画更新卡片
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
        val switches = vm.switches.value ?: return
        if (index !in switches.indices) return
        val sw = switches[index]

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除设备")
            .setMessage("确定要删除 \"${sw.name}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                vm.removeSwitch(index)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
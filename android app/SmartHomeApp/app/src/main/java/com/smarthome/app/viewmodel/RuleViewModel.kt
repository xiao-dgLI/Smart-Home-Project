package com.smarthome.app.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarthome.app.Constants
import com.smarthome.app.MainApplication
import com.smarthome.app.MainViewModel
import com.smarthome.app.model.Rule
import com.smarthome.app.model.RunTimeSlot
import com.smarthome.app.repository.CloudRepository
import com.smarthome.app.repository.DataStoreRepository
import com.smarthome.app.repository.NotificationRepository
import kotlinx.coroutines.launch


 // 规则 ViewModel — 规则CRUD、本地/云规则同步、定时任务管理

class RuleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MainApplication
    private val cloudRepository: CloudRepository = app.cloudRepository
    private val dataStoreRepository: DataStoreRepository = app.dataStoreRepository
    private val notificationRepository: NotificationRepository = app.notificationRepository

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTimeouts = mutableMapOf<Long, Runnable>()
    private val retryRunnables = mutableMapOf<Long, Runnable>()

    // 规则 CRUD

    fun addRule(mainVm: MainViewModel, rule: Rule) {
        val list = (mainVm.rules.value ?: mutableListOf()).toMutableList()
        list.add(rule)
        mainVm.updateRules(list)
        dataStoreRepository.saveRules(list)

        if (rule.isCloud) attemptCloudRuleSync(mainVm, list.size - 1)
        else attemptRuleSync(mainVm, list.size - 1)
    }

    fun removeRule(mainVm: MainViewModel, index: Int) {
        val list = (mainVm.rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        val rule = list[index]
        cancelPendingCallbacks(rule.id)

        if (rule.isCloud && rule.cloudStrategyId > 0) {
            cloudRepository.deleteStrategyAsync(listOf(rule.cloudStrategyId)) { success ->
                mainHandler.post {
                    mainVm.addLog(if (success) "云策略已删除: ${rule.name}" else "云策略删除失败: ${rule.name}")
                }
            }
        }
        list.removeAt(index)
        mainVm.updateRules(list)
        dataStoreRepository.saveRules(list)
    }

    fun toggleRule(mainVm: MainViewModel, index: Int) {
        val list = (mainVm.rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        val rule = list[index]
        rule.enabled = !rule.enabled
        mainVm.updateRules(list)
        dataStoreRepository.saveRules(list)

        if (rule.isCloud && rule.cloudStrategyId > 0) {
            cloudRepository.enableStrategyAsync(rule.cloudStrategyId, rule.enabled) { success ->
                mainHandler.post {
                    mainVm.addLog(
                        if (success) "云策略${if (rule.enabled) "启用" else "禁用"}: ${rule.name}"
                        else "云策略状态切换失败: ${rule.name}"
                    )
                }
            }
        } else if (!rule.isCloud) {
            rule.syncStatus = 0
            attemptRuleSync(mainVm, index)
        }
    }

    fun manualRetryRule(mainVm: MainViewModel, index: Int) {
        val list = (mainVm.rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        list[index].retryCount = 0
        list[index].syncStatus = 0
        mainVm.updateRules(list)
        dataStoreRepository.saveRules(list)

        if (list[index].isCloud) attemptCloudRuleSync(mainVm, index)
        else attemptRuleSync(mainVm, index)
    }

    // 本地规则同步

    fun syncAllPendingRules(mainVm: MainViewModel) {
        val list = mainVm.rules.value ?: return
        for (i in list.indices) {
            val rule = list[i]
            if (rule.syncStatus == 0 || rule.syncStatus == 2 || rule.syncStatus == 4) {
                if (rule.isCloud) attemptCloudRuleSync(mainVm, i) else attemptRuleSync(mainVm, i)
            }
        }
    }

    fun attemptRuleSync(mainVm: MainViewModel, index: Int) {
        val list = (mainVm.rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        val rule = list[index]
        if (rule.syncStatus == 3) return

        val cm = app.localRepository.connectionManager
        if (!cm.isConnected) {
            rule.syncStatus = 0
            mainVm.updateRules(list)
            dataStoreRepository.saveRules(list)
            return
        }

        cancelPendingCallbacks(rule.id)
        rule.syncStatus = 4
        rule.lastRetryTime = System.currentTimeMillis()
        mainVm.updateRules(list)
        dataStoreRepository.saveRules(list)

        cm.sendRule(rule)
        mainVm.addLog("规则上传: ${rule.name} (第${rule.retryCount + 1}次)")

        val timeoutRunnable = Runnable { onRuleSyncTimeout(mainVm, rule.id) }
        pendingTimeouts[rule.id] = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, Constants.RULE_SYNC_TIMEOUT_MS)
    }

    private fun onRuleSyncTimeout(mainVm: MainViewModel, ruleId: Long) {
        pendingTimeouts.remove(ruleId)
        val list = (mainVm.rules.value ?: return).toMutableList()
        val index = list.indexOfFirst { it.id == ruleId }
        if (index == -1) return
        val rule = list[index]

        rule.retryCount++
        mainVm.addLog("规则超时: ${rule.name} (第${rule.retryCount}次失败)")

        if (rule.retryCount >= Constants.MAX_RETRY_COUNT) {
            rule.syncStatus = 3
            mainVm.addLog("规则放弃: ${rule.name}，已达最大重试次数")
        } else {
            rule.syncStatus = 2
            val retryRunnable = Runnable { retryRuleSync(mainVm, ruleId) }
            retryRunnables[rule.id] = retryRunnable
            mainHandler.postDelayed(retryRunnable, Constants.RULE_RETRY_DELAY_MS)
        }
        mainVm.updateRules(list)
        dataStoreRepository.saveRules(list)
    }

    private fun retryRuleSync(mainVm: MainViewModel, ruleId: Long) {
        retryRunnables.remove(ruleId)
        val list = mainVm.rules.value ?: return
        val index = list.indexOfFirst { it.id == ruleId }
        if (index == -1) return
        attemptRuleSync(mainVm, index)
    }

    fun handleRuleAck(mainVm: MainViewModel, json: String) {
        try {
            val map = app.cloudRepository.nleCloudManager.gson.fromJson(json, Map::class.java)
            val ruleId = (map["rule_id"] as? Number)?.toLong() ?: return
            val status = map["status"] as? String ?: return
            val success = status == "ok"
            cancelPendingCallbacks(ruleId)

            val list = (mainVm.rules.value ?: return).toMutableList()
            val index = list.indexOfFirst { it.id == ruleId }
            if (index == -1) return
            val rule = list[index]

            if (success) {
                rule.syncStatus = 1
                rule.retryCount = 0
                mainVm.addLog("规则同步成功: ${rule.name}")
            } else {
                rule.retryCount++
                mainVm.addLog("规则同步失败: ${rule.name} (第${rule.retryCount}次)")
                if (rule.retryCount >= Constants.MAX_RETRY_COUNT) rule.syncStatus = 3
                else {
                    rule.syncStatus = 2
                    val retryRunnable = Runnable { retryRuleSync(mainVm, ruleId) }
                    retryRunnables[rule.id] = retryRunnable
                    mainHandler.postDelayed(retryRunnable, Constants.RULE_RETRY_DELAY_MS)
                }
            }
            mainVm.updateRules(list)
            dataStoreRepository.saveRules(list)
        } catch (_: Exception) {}
    }

    // 云平台规则同步

    fun attemptCloudRuleSync(mainVm: MainViewModel, index: Int) {
        val list = (mainVm.rules.value ?: return).toMutableList()
        if (index !in list.indices) return
        val rule = list[index]

        if (!cloudRepository.isConnected()) {
            rule.syncStatus = 0
            mainVm.updateRules(list)
            dataStoreRepository.saveRules(list)
            return
        }

        cancelPendingCallbacks(rule.id)
        rule.syncStatus = 4
        rule.lastRetryTime = System.currentTimeMillis()
        mainVm.updateRules(list)
        dataStoreRepository.saveRules(list)

        val gatewayDeviceId = mainVm.cloudDeviceIds().firstOrNull() ?: rule.cloudActuatorDeviceId

        if (rule.isTimedTask) {
            val actions = listOf(mapOf(
                "ApiTag" to rule.cloudActuatorApiTag,
                "SetValue" to rule.cloudActionValue,
                "Delay" to 0
            ))
            val runTimes = rule.runTimeSlots.map {
                mapOf("Period" to rule.runTimePeriod + 1, "Day" to rule.runTimeDay, "Time" to "")
            }

            mainVm.addLog("正在创建定时任务: ${rule.name} (${rule.runTimeSlots.size}个时间点)...")
            cloudRepository.addStrategyAsync(
                deviceId = gatewayDeviceId, kind = 1, expression = "",
                variables = emptyList(), actions = actions, runTimes = runTimes
            ) { strategyId ->
                mainHandler.post { handleCloudStrategyResult(mainVm, rule, strategyId) }
            }
        } else {
            val expression = "{${rule.cloudSensorName}}${rule.operator}${rule.threshold.toInt()}"
            val actions = listOf(mapOf(
                "ApiTag" to rule.cloudActuatorApiTag,
                "SetValue" to rule.cloudActionValue, "Delay" to 0
            ))
            val runTimes = listOf(mapOf("Period" to 1, "Day" to 0))

            mainVm.addLog("正在创建条件任务: ${rule.name}...")
            cloudRepository.addStrategyAsync(
                deviceId = gatewayDeviceId, kind = 1, expression = expression,
                variables = emptyList(), actions = actions, runTimes = runTimes
            ) { strategyId ->
                mainHandler.post { handleCloudStrategyResult(mainVm, rule, strategyId) }
            }
        }
    }

    private fun handleCloudStrategyResult(mainVm: MainViewModel, rule: Rule, strategyId: Int?) {
        val rules = (mainVm.rules.value ?: return).toMutableList()
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx == -1) return
        val r = rules[idx]

        if (strategyId != null) {
            r.cloudStrategyId = strategyId
            r.syncStatus = 1
            r.retryCount = 0
            r.enabled = false
            mainVm.addLog("策略创建成功: ${r.name} (ID=$strategyId)")
            mainHandler.postDelayed({ mainVm.loadCloudStrategies() }, 2000)
        } else {
            r.retryCount++
            mainVm.addLog("策略创建失败: ${r.name} (第${r.retryCount}次)")
            if (r.retryCount >= Constants.MAX_RETRY_COUNT) r.syncStatus = 3
            else {
                r.syncStatus = 2
                val retryRunnable = Runnable { retryCloudRuleSync(mainVm, r.id) }
                retryRunnables[r.id] = retryRunnable
                mainHandler.postDelayed(retryRunnable, Constants.RULE_RETRY_DELAY_MS)
            }
        }
        mainVm.updateRules(rules)
        dataStoreRepository.saveRules(rules)
    }

    private fun retryCloudRuleSync(mainVm: MainViewModel, ruleId: Long) {
        retryRunnables.remove(ruleId)
        val list = mainVm.rules.value ?: return
        val index = list.indexOfFirst { it.id == ruleId }
        if (index == -1) return
        attemptCloudRuleSync(mainVm, index)
    }

    // 条件任务检查

    private val ruleAlertTime = mutableMapOf<String, Long>()

    fun checkRules(mainVm: MainViewModel, data: com.smarthome.app.model.SensorData) {
        val sensorMap = mapOf(
            "temp" to data.temp.toDouble(), "humi" to data.humi.toDouble(),
            "light" to data.light.toDouble(), "pir" to data.pir.toDouble(),
            "flame" to data.flame.toDouble(), "gas" to data.gas.toDouble()
        )
        val rulesList = mainVm.rules.value ?: return
        val now = System.currentTimeMillis()

        for (rule in rulesList) {
            if (!rule.enabled || rule.isTimedTask) continue

            val sensorVal: Double = if (rule.isCloud) {
                val key = "${rule.cloudSensorDeviceId}:${rule.cloudSensorApiTag}"
                mainVm.cloudSensorValues.value[key]?.toDoubleOrNull() ?: continue
            } else {
                sensorMap[rule.sensorType] ?: continue
            }

            val threshold = rule.threshold.toDouble()
            val triggered = when (rule.operator) {
                ">" -> sensorVal > threshold; "<" -> sensorVal < threshold
                "==" -> sensorVal == threshold; ">=" -> sensorVal >= threshold
                "<=" -> sensorVal <= threshold; else -> false
            }

            if (triggered && canAlert("rule_${rule.id}", now)) {
                ruleAlertTime["rule_${rule.id}"] = now

                if (rule.isCloud && cloudRepository.isConnected()) {
                    cloudRepository.sendCommand(rule.cloudActuatorDeviceId, rule.cloudActuatorApiTag, rule.cloudActionValue) { success ->
                        mainHandler.post {
                            mainVm.addLog(if (success) "云规则执行成功: ${rule.name}" else "云规则执行失败: ${rule.name}")
                        }
                    }
                } else if (!rule.isCloud) {
                    val cm = app.localRepository.connectionManager
                    cm.sendCommand(rule.actionDevice, rule.actionCmd)
                    when (rule.actionDevice) {
                        "light" -> mainVm.updateLightState(rule.actionCmd == "on")
                        "fan" -> mainVm.updateFanState(rule.actionCmd == "on")
                    }
                }

                val desc = if (rule.isCloud) {
                    val sName = rule.cloudSensorName.ifBlank { rule.cloudSensorApiTag }
                    val aName = rule.cloudActuatorName.ifBlank { rule.cloudActuatorApiTag }
                    val cmdName = if (rule.cloudActionValue == "1") "开启" else "关闭"
                    "当$sName${rule.operator}${rule.threshold}时，$cmdName$aName"
                } else {
                    val devName = if (rule.actionDevice == "light") "灯光" else "风扇"
                    val cmdName = if (rule.actionCmd == "on") "开启" else "关闭"
                    "当${rule.sensorType}${rule.operator}${rule.threshold}时，$cmdName$devName"
                }
                notificationRepository.sendRuleNotification(rule.name, desc)
                mainVm.addLog("任务触发: ${rule.name}")
            }
        }
    }

    // 辅助

    private fun cancelPendingCallbacks(ruleId: Long) {
        pendingTimeouts.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
        retryRunnables.remove(ruleId)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun canAlert(key: String, now: Long): Boolean {
        val last = ruleAlertTime[key] ?: 0L
        return (now - last) > Constants.ALERT_COOLDOWN_MS
    }
}

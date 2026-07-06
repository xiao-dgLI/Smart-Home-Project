package com.smarthome.app.cloud

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

class NleCloudManager(private val context: Context) {

    companion object {
        private const val TAG = "NleCloud"
        private const val BASE_URL = "https://api.nlecloud.com"
        private const val PREF_NAME = "nlecloud_prefs"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val gson = GsonBuilder().disableHtmlEscaping().create()
    private var accessToken: String? = null

    // OkHttp 客户端（支持连接复用）
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    // 凭证管理

    fun getSavedCredentials(): Pair<String, String> {
        return Pair(
            prefs.getString("account", "") ?: "",
            prefs.getString("password", "") ?: ""
        )
    }

    private fun saveCredentials(account: String, password: String) {
        prefs.edit()
            .putString("account", account)
            .putString("password", password)
            .apply()
    }

    fun saveMqttCredentials(tag: String, securityKey: String) {
        prefs.edit()
            .putString("mqtt_tag", tag)
            .putString("mqtt_security_key", securityKey)
            .apply()
    }

    fun getSavedTag(): String = prefs.getString("mqtt_tag", "") ?: ""
    fun getSavedSecurityKey(): String = prefs.getString("mqtt_security_key", "") ?: ""

    // 登录
    fun login(account: String, password: String, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL/Users/Login"
                Log.d(TAG, "POST $url")

                val body = """{"Account":"$account","Password":"$password"}"""
                val resp = httpPost(url, body)
                Log.d(TAG, "Login: ${resp.take(300)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(false, "API 地址错误，返回了 HTML")
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val resultObj = json["ResultObj"] as? Map<*, *>
                accessToken = resultObj?.get("AccessToken") as? String

                if (accessToken != null && accessToken!!.isNotBlank()) {
                    Log.d(TAG, "登录成功 AccessToken=${accessToken?.take(20)}...")
                    saveCredentials(account, password)
                    callback(true, "登录成功")
                } else {
                    val msg = json["Msg"] as? String ?: "未获取到 AccessToken"
                    callback(false, msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                callback(false, e.message ?: "网络错误")
            }
        }.start()
    }

    // 获取项目列表
    fun getProjects(callback: (List<ProjectInfo>) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL/Projects"
                Log.d(TAG, "GET $url")
                val resp = httpGet(url)
                Log.d(TAG, "Projects: ${resp.take(500)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(emptyList())
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val resultObj = json["ResultObj"] as? Map<*, *>
                val pageSet = resultObj?.get("PageSet") as? List<*> ?: emptyList<Any>()

                val projects = pageSet.mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    ProjectInfo(
                        ProjectID = (map["ProjectID"] as? Number)?.toInt() ?: 0,
                        Name = map["Name"] as? String ?: ""
                    )
                }

                Log.d(TAG, "获取到 ${projects.size} 个项目")
                for (p in projects) {
                    Log.d(TAG, "  项目: ${p.Name} (ID=${p.ProjectID})")
                }

                callback(projects)
            } catch (e: Exception) {
                Log.e(TAG, "GetProjects failed", e)
                callback(emptyList())
            }
        }.start()
    }

    // 连接项目
    fun connectToProject(projectId: Int, callback: (Boolean) -> Unit) {
        callback(accessToken != null)
    }

    // 获取设备列表
    fun getDevicesByProject(
        projectId: Int,
        callback: (List<DeviceBaseInfo>) -> Unit
    ) {
        Thread {
            try {
                val url = "$BASE_URL/Devices?ProjectKeyWord=$projectId&PageSize=100"
                Log.d(TAG, "GET $url")
                val resp = httpGet(url)
                Log.d(TAG, "Devices: ${resp.take(500)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(emptyList())
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val resultObj = json["ResultObj"] as? Map<*, *>
                val pageSet = resultObj?.get("PageSet") as? List<*> ?: emptyList<Any>()

                val devices = pageSet.mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val devId = (map["DeviceID"] as? Number)?.toInt() ?: 0
                    val name = map["Name"] as? String ?: ""
                    val tag = map["Tag"] as? String ?: ""
                    val securityKey = map["SecurityKey"] as? String ?: ""

                    Log.d(TAG, "设备: $name (ID=$devId, Tag=$tag, Key=${securityKey.take(8)}...)")

                    DeviceBaseInfo(
                        DeviceID = devId,
                        Name = name,
                        IsOnline = map["IsOnline"] as? Boolean ?: false,
                        ProjectID = projectId,
                        SerialNumber = tag,
                        SecretKey = securityKey
                    )
                }

                Log.d(TAG, "获取到 ${devices.size} 个设备")
                callback(devices)
            } catch (e: Exception) {
                Log.e(TAG, "GetDevices failed", e)
                callback(emptyList())
            }
        }.start()
    }

    // 获取设备详情
    fun getDeviceDetail(
        deviceId: Int,
        callback: (DeviceBaseInfo?) -> Unit
    ) {
        Thread {
            try {
                val url = "$BASE_URL/Devices/$deviceId"
                Log.d(TAG, "GET $url")
                val resp = httpGet(url)
                Log.d(TAG, "DeviceDetail: ${resp.take(500)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(null)
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val result = json["ResultObj"] as? Map<*, *>

                if (result != null) {
                    Log.d(TAG, "返回字段: ${result.keys.joinToString(", ")}")

                    val info = DeviceBaseInfo(
                        DeviceID = (result["DeviceID"] as? Number)?.toInt() ?: deviceId,
                        Name = result["Name"] as? String ?: "",
                        IsOnline = result["IsOnline"] as? Boolean ?: false,
                        ProjectID = (result["ProjectID"] as? Number)?.toInt() ?: 0,
                        SerialNumber = result["Tag"] as? String ?: "",
                        SecretKey = result["SecurityKey"] as? String ?: ""
                    )

                    Log.d(TAG, "  Tag=${info.SerialNumber}")
                    Log.d(TAG, "  SecurityKey=${info.SecretKey.take(10)}...")

                    callback(info)
                } else {
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "GetDeviceDetail failed", e)
                callback(null)
            }
        }.start()
    }

    // 获取传感器最新数据
    fun getSensors(devIds: String, callback: (List<SensorPoint>) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL/Devices/Datas?devIds=$devIds"
                Log.d(TAG, "========== 获取传感器数据 ==========")
                Log.d(TAG, "请求地址: GET $url")
                Log.d(TAG, "设备ID列表: $devIds")

                val resp = httpGet(url)
                Log.d(TAG, "响应状态: ${resp.take(100)}...")

                if (resp.trimStart().startsWith("<")) {
                    Log.w(TAG, "返回了HTML而不是JSON，可能需要重新登录")
                    callback(emptyList())
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                val msg = json["Msg"] as? String ?: ""
                Log.d(TAG, "API状态: Status=$status, Msg=$msg")

                val resultObj = json["ResultObj"] as? List<*> ?: emptyList<Any>()
                Log.d(TAG, "设备数量: ${resultObj.size}")

                val sensors = mutableListOf<SensorPoint>()
                for (item in resultObj) {
                    val deviceMap = item as? Map<*, *> ?: continue
                    val devId = (deviceMap["DeviceID"] as? Number)?.toInt() ?: 0
                    val devName = deviceMap["Name"] as? String ?: ""
                    val datas = deviceMap["Datas"] as? List<*> ?: continue

                    Log.d(TAG, "设备: $devName (ID=$devId), 数据点: ${datas.size}")

                    for (data in datas) {
                        val dataMap = data as? Map<*, *> ?: continue
                        val apiTag = dataMap["ApiTag"] as? String ?: ""
                        val value = (dataMap["Value"] as? Number)?.toString()
                            ?: (dataMap["Value"] as? String ?: "--")
                        val recordTime = dataMap["RecordTime"] as? String ?: ""

                        Log.d(TAG, "  传感器: $apiTag = $value (时间: $recordTime)")

                        sensors.add(
                            SensorPoint(
                                DeviceID = devId,
                                DeviceName = devName,
                                ApiTag = apiTag,
                                Name = apiTag,
                                Value = value,
                                Unit = "",
                                At = recordTime
                            )
                        )
                    }
                }

                Log.d(TAG, "共获取 ${sensors.size} 个传感器数据点")
                Log.d(TAG, "================================")
                callback(sensors)
            } catch (e: Exception) {
                Log.e(TAG, "获取传感器数据失败: ${e.message}", e)
                callback(emptyList())
            }
        }.start()
    }

    // 查询项目下所有传感器
    fun getProjectSensors(
        projectId: Int,
        callback: (List<SensorPoint>) -> Unit
    ) {
        Thread {
            try {
                val url = "$BASE_URL/Projects/$projectId/Sensors"
                Log.d(TAG, "========== 获取项目传感器元数据 ==========")
                Log.d(TAG, "请求地址: GET $url")
                Log.d(TAG, "项目ID: $projectId")

                val resp = httpGet(url)
                Log.d(TAG, "响应: ${resp.take(200)}...")

                if (resp.trimStart().startsWith("<")) {
                    Log.w(TAG, "返回HTML，可能需要重新登录")
                    callback(emptyList())
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                Log.d(TAG, "API状态: Status=$status")

                val resultObj = json["ResultObj"] as? List<*> ?: emptyList<Any>()
                Log.d(TAG, "传感器总数: ${resultObj.size}")

                val sensors = resultObj.mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val name = map["Name"] as? String ?: ""
                    val apiTag = map["ApiTag"] as? String ?: ""
                    val unit = map["Unit"] as? String ?: ""
                    val transType = (map["TransType"] as? Number)?.toInt() ?: 0

                    Log.d(TAG, "  传感器: $name ($apiTag) 单位=$unit TransType=$transType")

                    SensorPoint(
                        DeviceID = (map["DeviceID"] as? Number)?.toInt() ?: 0,
                        DeviceName = "",
                        ApiTag = apiTag,
                        Name = name,
                        Value = (map["Value"] as? Number)?.toString()
                            ?: (map["Value"] as? String ?: "--"),
                        Unit = unit,
                        At = map["RecordTime"] as? String ?: "",
                        TransType = transType,
                        OperType = (map["OperType"] as? Number)?.toInt() ?: 0,
                        SensorType = map["SensorType"] as? String ?: ""
                    )
                }

                Log.d(TAG, "共解析 ${sensors.size} 个传感器")
                Log.d(TAG, "================================")
                callback(sensors)
            } catch (e: Exception) {
                Log.e(TAG, "获取项目传感器失败: ${e.message}", e)
                callback(emptyList())
            }
        }.start()
    }

    // 查询单个设备下的传感器/执行器（获取真实 DeviceID）
    fun getDeviceSensors(
        deviceId: Int,
        apiTags: String = "",
        callback: (List<SensorPoint>) -> Unit
    ) {
        Thread {
            try {
                val url = "$BASE_URL/Devices/$deviceId/Sensors?apiTags=$apiTags"
                Log.d(TAG, "========== 获取设备传感器详情 ==========")
                Log.d(TAG, "请求地址: GET $url")
                Log.d(TAG, "设备ID: $deviceId")

                val resp = httpGet(url)
                Log.d(TAG, "响应: ${resp.take(200)}...")

                if (resp.trimStart().startsWith("<")) {
                    Log.w(TAG, "返回HTML，可能需要重新登录")
                    callback(emptyList())
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                Log.d(TAG, "API状态: Status=$status")

                val resultObj = json["ResultObj"] as? List<*> ?: emptyList<Any>()
                Log.d(TAG, "传感器/执行器数量: ${resultObj.size}")

                val sensors = resultObj.mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val sDeviceId = (map["DeviceID"] as? Number)?.toInt() ?: 0
                    val sApiTag = map["ApiTag"] as? String ?: ""
                    val sName = map["Name"] as? String ?: ""
                    val sOperType = (map["OperType"] as? Number)?.toInt() ?: 0
                    val unit = map["Unit"] as? String ?: ""

                    val type = if (sOperType > 0) "执行器" else "传感器"
                    Log.d(TAG, "  $type: $sName ($sApiTag) DeviceID=$sDeviceId 单位=$unit OperType=$sOperType")

                    SensorPoint(
                        DeviceID = sDeviceId,
                        DeviceName = "",
                        ApiTag = sApiTag,
                        Name = sName,
                        Value = "",
                        Unit = unit,
                        At = "",
                        TransType = (map["TransType"] as? Number)?.toInt() ?: 0,
                        OperType = sOperType,
                        SensorType = map["SensorType"] as? String ?: ""
                    )
                }

                Log.d(TAG, "共解析 ${sensors.size} 个传感器/执行器")
                Log.d(TAG, "================================")
                callback(sensors)
            } catch (e: Exception) {
                Log.e(TAG, "获取设备传感器失败: ${e.message}", e)
                callback(emptyList())
            }
        }.start()
    }

    // 发送控制指令（使用OkHttp连接复用）
    fun sendCommand(
        deviceId: Int,
        apiTag: String,
        value: String,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val command = when (value.trim()) {
            "1", "true", "True", "TRUE", "on", "ON" -> "1"
            "0", "false", "False", "FALSE", "off", "OFF" -> "0"
            else -> "0"
        }

        Thread {
            try {
                val url = "$BASE_URL/Cmds?deviceId=$deviceId&apiTag=$apiTag"
                val body = command.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("AccessToken", accessToken ?: "")
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val resp = response.body?.string() ?: ""
                response.close()

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                callback?.invoke(status == 0)

            } catch (e: Exception) {
                Log.e(TAG, "SendCommand failed: ${e.message}")
                callback?.invoke(false)
            }
        }.start()
    }

    // ========== 策略 API ==========

    fun getStrategies(projectId: Int, callback: (List<Map<String, Any?>>) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL/Strategys?ProjectID=$projectId&PageSize=100&PageIndex=1"
                Log.d(TAG, "GET $url")
                val resp = httpGet(url)
                Log.d(TAG, "Strategies: ${resp.take(500)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(emptyList())
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val resultObj = json["ResultObj"] as? Map<*, *>
                val pageSet = resultObj?.get("PageSet") as? List<*> ?: emptyList<Any>()

                @Suppress("UNCHECKED_CAST")
                val strategies = pageSet.mapNotNull { it as? Map<String, Any?> }

                Log.d(TAG, "获取到 ${strategies.size} 条策略")
                for (s in strategies) {
                    val sid = s["StrategyId"]
                    val sname = s["GatewayName"]
                    val vars = s["VariableList"]
                    val acts = s["ActionList"]
                    val runs = s["RunTimeList"]
                    Log.d(TAG, "  策略 ID=$sid 名=$sname 变量=$vars 动作=$acts 定时=$runs")
                }

                callback(strategies)
            } catch (e: Exception) {
                Log.e(TAG, "GetStrategies failed", e)
                callback(emptyList())
            }
        }.start()
    }

    fun addStrategy(
        deviceId: Int,
        kind: Int,
        expression: String,
        variables: List<Map<String, Any>>,
        actions: List<Map<String, Any>>,
        runTimes: List<Map<String, Any>> = emptyList(),
        callback: (Int?) -> Unit
    ) {
        Thread {
            try {
                val url = "$BASE_URL/Strategys"
                val bodyMap = mapOf(
                    "DeviceID" to deviceId,
                    "Kind" to kind,
                    "Expression" to expression,
                    "StrategyVariableList" to variables,
                    "StrategyActionList" to actions,
                    "StrategyRunTimeList" to runTimes
                )
                val body = gson.toJson(bodyMap)
                Log.d(TAG, "POST Strategys body=$body")
                val resp = httpPost(url, body)
                Log.d(TAG, "AddStrategy: ${resp.take(300)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(null)
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                val strategyId = (json["ResultObj"] as? Number)?.toInt()

                if (status == 0 && strategyId != null) {
                    Log.d(TAG, "策略创建成功 ID=$strategyId")
                    callback(strategyId)
                } else {
                    val msg = json["Msg"] as? String ?: "未知错误"
                    Log.w(TAG, "AddStrategy failed: $msg")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AddStrategy failed", e)
                callback(null)
            }
        }.start()
    }

    fun addStrategyWeb(bodyMap: Map<String, Any>, callback: (Int?) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL/Strategys"
                val body = gson.toJson(bodyMap)
                Log.d(TAG, "POST Strategys(body) body=$body")
                val resp = httpPost(url, body)
                Log.d(TAG, "AddStrategyWeb: ${resp.take(500)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(null)
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                val strategyId = (json["ResultObj"] as? Number)?.toInt()

                if (status == 0 && strategyId != null) {
                    Log.d(TAG, "策略创建成功 ID=$strategyId")
                    callback(strategyId)
                } else {
                    val msg = json["Msg"] as? String ?: "未知错误"
                    Log.w(TAG, "AddStrategyWeb failed: $msg")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AddStrategyWeb failed", e)
                callback(null)
            }
        }.start()
    }

    fun deleteStrategy(strategyIds: List<Int>, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL/Strategys"
                val body = gson.toJson(strategyIds)
                Log.d(TAG, "DELETE Strategys body=$body")
                val resp = httpDelete(url, body)
                Log.d(TAG, "DeleteStrategy: ${resp.take(300)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(false)
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                callback(status == 0)
            } catch (e: Exception) {
                Log.e(TAG, "DeleteStrategy failed", e)
                callback(false)
            }
        }.start()
    }

    fun enableStrategy(strategyId: Int, enable: Boolean, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val url = "$BASE_URL/Strategys/Enable/$strategyId?enable=$enable"
                Log.d(TAG, "POST $url")
                val resp = httpPost(url, "")
                Log.d(TAG, "EnableStrategy: ${resp.take(300)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(false)
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                callback(status == 0)
            } catch (e: Exception) {
                Log.e(TAG, "EnableStrategy failed", e)
                callback(false)
            }
        }.start()
    }

    fun updateStrategy(
        strategyId: Int,
        deviceId: Int,
        kind: Int,
        expression: String,
        variables: List<Map<String, Any>>,
        actions: List<Map<String, Any>>,
        callback: (Boolean) -> Unit
    ) {
        Thread {
            try {
                val url = "$BASE_URL/Strategys/$strategyId"
                val bodyMap = mapOf(
                    "DeviceID" to deviceId,
                    "Kind" to kind,
                    "Expression" to expression,
                    "StrategyVariableList" to variables,
                    "StrategyActionList" to actions,
                    "StrategyRunTimeList" to emptyList<Any>()
                )
                val body = gson.toJson(bodyMap)
                Log.d(TAG, "PUT Strategys/$strategyId body=$body")
                val resp = httpPut(url, body)
                Log.d(TAG, "UpdateStrategy: ${resp.take(300)}")

                if (resp.trimStart().startsWith("<")) {
                    callback(false)
                    return@Thread
                }

                val json = gson.fromJson(resp, Map::class.java)
                val status = (json["Status"] as? Number)?.toInt() ?: -1
                callback(status == 0)
            } catch (e: Exception) {
                Log.e(TAG, "UpdateStrategy failed", e)
                callback(false)
            }
        }.start()
    }

    // 状态
    fun isConnected(): Boolean = accessToken != null
    fun getAccessToken(): String? = accessToken
    fun disconnect() { accessToken = null }

    // OkHttp 工具方法

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }
            .build()
        val response = httpClient.newCall(request).execute()
        val resp = response.body?.string() ?: ""
        response.close()
        return resp
    }

    private fun httpPost(url: String, body: String): String {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }
            .build()
        val response = httpClient.newCall(request).execute()
        val resp = response.body?.string() ?: ""
        response.close()
        return resp
    }

    private fun httpPut(url: String, body: String): String {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }
            .build()
        val response = httpClient.newCall(request).execute()
        val resp = response.body?.string() ?: ""
        response.close()
        return resp
    }

    private fun httpDelete(url: String, body: String? = null): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .delete()
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }
        if (body != null) {
            requestBuilder.post(body.toRequestBody("application/json".toMediaType()))
        }
        val response = httpClient.newCall(requestBuilder.build()).execute()
        val resp = response.body?.string() ?: ""
        response.close()
        return resp
    }
}
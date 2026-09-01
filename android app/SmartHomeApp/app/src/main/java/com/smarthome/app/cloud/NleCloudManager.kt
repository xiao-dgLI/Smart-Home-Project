package com.smarthome.app.cloud

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp 保活拦截器：复用连接时自动发送轻量请求检测连接健康
 * 当连接空闲超过阈值时，在真正请求前先发一个 HEAD 探测
 */
class KeepAliveInterceptor : Interceptor {
    companion object {
        private const val TAG = "KeepAlive"
        private const val STALE_MAX_AGE = 60_000L
    }

    private val lastUsedTime = AtomicLong(0L)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val connection = chain.connection()

        if (connection != null) {
            val age = System.currentTimeMillis() - lastUsedTime.get()
            if (lastUsedTime.get() > 0 && age > STALE_MAX_AGE) {
                Log.d(TAG, "连接空闲 ${age}ms，发送 HEAD 探测")
                try {
                    val headRequest = Request.Builder()
                        .url(request.url)
                        .head()
                        .build()
                    chain.proceed(headRequest).close()
                } catch (e: IOException) {
                    Log.w(TAG, "连接已失效，将使用新连接: ${e.message}")
                }
            }
        }

        lastUsedTime.set(System.currentTimeMillis())
        return chain.proceed(request)
    }
}

class NleCloudManager(private val context: Context) {

    companion object {
        private const val TAG = "NleCloud"
        private const val BASE_URL = "https://api.nlecloud.com"
        private const val PREF_NAME = "nlecloud_prefs"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val gson = GsonBuilder().disableHtmlEscaping().create()
    private var accessToken: String? = null

    // OkHttp 客户端 — dispatcher 线程池 + 保活拦截器
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .addInterceptor(KeepAliveInterceptor())
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        })
        .build()

    // ========== 异步辅助方法 ==========

    /**
     * 异步 GET — 使用 OkHttp dispatcher 线程池，回调在子线程执行
     * 调用方需自行切主线程（mainHandler.post）
     */
    private fun httpGetAsync(url: String, callback: (String) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "GET $url failed: ${e.message}")
                callback("")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    callback(resp.body?.string() ?: "")
                }
            }
        })
    }

    /**
     * 异步 POST — 使用 OkHttp dispatcher 线程池
     */
    private fun httpPostAsync(url: String, body: String, callback: (String) -> Unit) {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "POST $url failed: ${e.message}")
                callback("")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    callback(resp.body?.string() ?: "")
                }
            }
        })
    }

    /**
     * 异步 PUT
     */
    private fun httpPutAsync(url: String, body: String, callback: (String) -> Unit) {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "PUT $url failed: ${e.message}")
                callback("")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    callback(resp.body?.string() ?: "")
                }
            }
        })
    }

    /**
     * 异步 DELETE
     */
    private fun httpDeleteAsync(url: String, body: String? = null, callback: (String) -> Unit) {
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .apply { accessToken?.let { addHeader("AccessToken", it) } }

        if (body != null) {
            requestBuilder.delete(body.toRequestBody("application/json".toMediaType()))
        } else {
            requestBuilder.delete()
        }

        httpClient.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "DELETE $url failed: ${e.message}")
                callback("")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    callback(resp.body?.string() ?: "")
                }
            }
        })
    }

    // ========== 响应解析辅助 ==========

    /** 解析 API 响应，返回 ResultObj Map，失败返回 null */
    private fun parseResultObj(resp: String): Map<*, *>? {
        if (resp.isBlank() || resp.trimStart().startsWith("<")) return null
        return try {
            val json = gson.fromJson(resp, Map::class.java)
            json["ResultObj"] as? Map<*, *>
        } catch (e: Exception) {
            Log.e(TAG, "parseResultObj failed: ${e.message}")
            null
        }
    }

    /** 解析 API 响应，返回 (Status, Msg, ResultObj)，用于需要检查 Status 的场景 */
    private fun parseApiResponse(resp: String): Triple<Int, String, Any?> {
        if (resp.isBlank() || resp.trimStart().startsWith("<")) return Triple(-1, "无响应", null)
        return try {
            val json = gson.fromJson(resp, Map::class.java)
            val status = (json["Status"] as? Number)?.toInt() ?: -1
            val statusCode = (json["StatusCode"] as? Number)?.toInt()
            val msg = json["Msg"] as? String ?: ""
            val result = json["ResultObj"]
            Log.d(TAG, "API响应 Status=$status StatusCode=$statusCode Msg=$msg")
            Triple(status, msg, result)
        } catch (e: Exception) {
            Log.e(TAG, "parseApiResponse failed: ${e.message}")
            Triple(-1, e.message ?: "解析错误", null)
        }
    }

    // ========== 凭证管理 ==========

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
        val url = "$BASE_URL/Users/Login"
        val body = """{"Account":"$account","Password":"$password"}"""
        Log.d(TAG, "POST $url")

        httpPostAsync(url, body) { resp ->
            Log.d(TAG, "Login: ${resp.take(300)}")
            val (status, msg, resultObj) = parseApiResponse(resp)

            if (status == -1 && msg == "无响应") {
                callback(false, "网络无响应")
                return@httpPostAsync
            }

            try {
                val resultMap = resultObj as? Map<*, *>
                accessToken = resultMap?.get("AccessToken") as? String

                if (accessToken != null && accessToken!!.isNotBlank()) {
                    Log.d(TAG, "登录成功 AccessToken=${accessToken?.take(20)}...")
                    saveCredentials(account, password)
                    callback(true, "登录成功")
                } else {
                    callback(false, msg.ifBlank { "未获取到 AccessToken" })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login parse failed", e)
                callback(false, e.message ?: "解析错误")
            }
        }
    }

    // 获取项目列表
    fun getProjects(callback: (List<ProjectInfo>) -> Unit) {
        val url = "$BASE_URL/Projects"
        Log.d(TAG, "GET $url")

        httpGetAsync(url) { resp ->
            val (_, _, resultObj) = parseApiResponse(resp)
            val resultMap = resultObj as? Map<*, *>
            val pageSet = resultMap?.get("PageSet") as? List<*> ?: emptyList<Any>()

            val projects = pageSet.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                ProjectInfo(
                    ProjectID = (map["ProjectID"] as? Number)?.toInt() ?: 0,
                    Name = map["Name"] as? String ?: ""
                )
            }

            Log.d(TAG, "获取到 ${projects.size} 个项目")
            callback(projects)
        }
    }

    // 连接项目
    fun connectToProject(projectId: Int, callback: (Boolean) -> Unit) {
        callback(accessToken != null)
    }

    // 创建项目
    fun createProject(name: String, industry: Int = 2, netWorkKind: Int = 1, remark: String = "", callback: (Int?) -> Unit) {
        val url = "$BASE_URL/Projects"
        val body = gson.toJson(mapOf(
            "Name" to name,
            "Industry" to industry,
            "NetWorkKind" to netWorkKind,
            "Remark" to remark
        ))
        Log.d(TAG, "POST $url body=$body")

        httpPostAsync(url, body) { resp ->
            val (status, msg, resultObj) = parseApiResponse(resp)
            Log.d(TAG, "createProject Status=$status Msg=$msg ResultObj=$resultObj")

            if (status == 0 && resultObj is Number) {
                callback(resultObj.toInt())
            } else {
                callback(null)
            }
        }
    }

    // 删除项目
    fun deleteProject(projectIds: List<Int>, callback: (Boolean) -> Unit) {
        val url = "$BASE_URL/Projects"
        Log.d(TAG, "DELETE $url body=$projectIds")

        httpDeleteAsync(url, gson.toJson(projectIds)) { resp ->
            val (status) = parseApiResponse(resp)
            callback(status == 0)
        }
    }

    // 创建设备
    fun createDevice(projectId: Int, name: String, tag: String, protocol: Int = 2, callback: (Int?, String) -> Unit) {
        val url = "$BASE_URL/Devices"
        val body = gson.toJson(mapOf(
            "ProjectIdOrTag" to projectId.toString(),
            "Name" to name,
            "Tag" to tag,
            "Protocol" to protocol,
            "IsTrans" to true,
            "IsShare" to true
        ))
        Log.d(TAG, "POST $url body=$body")

        httpPostAsync(url, body) { resp ->
            val (status, msg, resultObj) = parseApiResponse(resp)
            Log.d(TAG, "createDevice Status=$status Msg=$msg ResultObj=$resultObj")

            if (status == 0 && resultObj is Number) {
                callback(resultObj.toInt(), "")
            } else {
                val errorMsg = msg.ifBlank { "设备创建失败" }
                callback(null, errorMsg)
            }
        }
    }

    // 删除设备
    fun deleteDevice(deviceId: Int, callback: (Boolean) -> Unit) {
        val url = "$BASE_URL/Devices/$deviceId"
        Log.d(TAG, "DELETE $url")

        httpDeleteAsync(url) { resp ->
            val (status) = parseApiResponse(resp)
            callback(status == 0)
        }
    }

    // 获取设备列表
    fun getDevicesByProject(projectId: Int, callback: (List<DeviceBaseInfo>) -> Unit) {
        val url = "$BASE_URL/Devices?ProjectKeyWord=$projectId&PageSize=100"
        Log.d(TAG, "GET $url")

        httpGetAsync(url) { resp ->
            val (_, _, resultObj) = parseApiResponse(resp)
            val resultMap = resultObj as? Map<*, *>
            val pageSet = resultMap?.get("PageSet") as? List<*> ?: emptyList<Any>()

            val devices = pageSet.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                DeviceBaseInfo(
                    DeviceID = (map["DeviceID"] as? Number)?.toInt() ?: 0,
                    Name = map["Name"] as? String ?: "",
                    IsOnline = map["IsOnline"] as? Boolean ?: false,
                    ProjectID = projectId,
                    SerialNumber = map["Tag"] as? String ?: "",
                    SecretKey = map["SecurityKey"] as? String ?: "",
                    Protocol = (map["Protocol"] as? Number)?.toInt() ?: 2
                )
            }

            Log.d(TAG, "获取到 ${devices.size} 个设备")
            callback(devices)
        }
    }

    // 获取设备详情
    fun getDeviceDetail(deviceId: Int, callback: (DeviceBaseInfo?) -> Unit) {
        val url = "$BASE_URL/Devices/$deviceId"
        Log.d(TAG, "GET $url")

        httpGetAsync(url) { resp ->
            val resultObj = parseResultObj(resp)
            if (resultObj == null) { callback(null); return@httpGetAsync }

            callback(DeviceBaseInfo(
                DeviceID = (resultObj["DeviceID"] as? Number)?.toInt() ?: deviceId,
                Name = resultObj["Name"] as? String ?: "",
                IsOnline = resultObj["IsOnline"] as? Boolean ?: false,
                ProjectID = (resultObj["ProjectID"] as? Number)?.toInt() ?: 0,
                SerialNumber = resultObj["Tag"] as? String ?: "",
                SecretKey = resultObj["SecurityKey"] as? String ?: ""
            ))
        }
    }

    // 批量查询设备在线状态
    fun getDevicesStatus(deviceIds: List<Int>, callback: (Map<Int, Boolean>) -> Unit) {
        val devIds = deviceIds.joinToString(",")
        val url = "$BASE_URL/Devices/Status?devIds=$devIds"
        Log.d(TAG, "GET $url")

        httpGetAsync(url) { resp ->
            Log.d(TAG, "getDevicesStatus 响应: ${resp.take(500)}")
            val (status, msg, resultObj) = parseApiResponse(resp)
            if (status != 0) {
                Log.e(TAG, "getDevicesStatus failed: Status=$status Msg=$msg")
                callback(emptyMap())
                return@httpGetAsync
            }

            try {
                val resultList = resultObj as? List<*> ?: emptyList<Any>()
                val statusMap = mutableMapOf<Int, Boolean>()
                for (item in resultList) {
                    val map = item as? Map<*, *> ?: continue
                    val deviceId = (map["DeviceID"] as? Number)?.toInt() ?: continue
                    val isOnline = map["IsOnline"] as? Boolean ?: false
                    statusMap[deviceId] = isOnline
                }
                Log.d(TAG, "设备在线状态: ${statusMap.map { "${it.key}=${if (it.value) "在线" else "离线"}" }}")
                callback(statusMap)
            } catch (e: Exception) {
                Log.e(TAG, "getDevicesStatus parse failed", e)
                callback(emptyMap())
            }
        }
    }

    // 获取传感器最新数据
    fun getSensors(devIds: String, callback: (List<SensorPoint>) -> Unit) {
        val url = "$BASE_URL/Devices/Datas?devIds=$devIds"
        Log.d(TAG, "GET $url (设备IDs: $devIds)")

        httpGetAsync(url) { resp ->
            val (status, msg, resultObj) = parseApiResponse(resp)
            Log.d(TAG, "getSensors Status=$status Msg=$msg")

            if (status == -1) {
                callback(emptyList())
                return@httpGetAsync
            }

            try {
                val resultList = resultObj as? List<*> ?: emptyList<Any>()
                val sensors = mutableListOf<SensorPoint>()

                for (item in resultList) {
                    val deviceMap = item as? Map<*, *> ?: continue
                    val devId = (deviceMap["DeviceID"] as? Number)?.toInt() ?: 0
                    val devName = deviceMap["Name"] as? String ?: ""
                    val datas = deviceMap["Datas"] as? List<*> ?: continue

                    for (data in datas) {
                        val dataMap = data as? Map<*, *> ?: continue
                        sensors.add(SensorPoint(
                            DeviceID = devId,
                            DeviceName = devName,
                            ApiTag = dataMap["ApiTag"] as? String ?: "",
                            Name = dataMap["ApiTag"] as? String ?: "",
                            Value = (dataMap["Value"] as? Number)?.toString()
                                ?: (dataMap["Value"] as? String ?: "--"),
                            Unit = "",
                            At = dataMap["RecordTime"] as? String ?: ""
                        ))
                    }
                }

                Log.d(TAG, "共获取 ${sensors.size} 个传感器数据点")
                for (s in sensors) {
                    Log.d(TAG, "  设备=${s.DeviceName}(ID=${s.DeviceID}) ApiTag=${s.ApiTag} Value=${s.Value} Time=${s.At}")
                }
                callback(sensors)
            } catch (e: Exception) {
                Log.e(TAG, "getSensors parse failed", e)
                callback(emptyList())
            }
        }
    }

    // 查询项目下所有传感器
    fun getProjectSensors(projectId: Int, callback: (List<SensorPoint>) -> Unit) {
        val url = "$BASE_URL/Projects/$projectId/Sensors"
        Log.d(TAG, "GET $url (项目ID: $projectId)")

        httpGetAsync(url) { resp ->
            val (_, _, resultObj) = parseApiResponse(resp)
            val resultList = resultObj as? List<*> ?: emptyList<Any>()

            val sensors = resultList.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                SensorPoint(
                    DeviceID = (map["DeviceID"] as? Number)?.toInt() ?: 0,
                    DeviceName = "",
                    ApiTag = map["ApiTag"] as? String ?: "",
                    Name = map["Name"] as? String ?: "",
                    Value = (map["Value"] as? Number)?.toString() ?: (map["Value"] as? String ?: "--"),
                    Unit = map["Unit"] as? String ?: "",
                    At = map["RecordTime"] as? String ?: "",
                    TransType = (map["TransType"] as? Number)?.toInt() ?: 0,
                    OperType = (map["OperType"] as? Number)?.toInt() ?: 0,
                    SensorType = map["SensorType"] as? String ?: ""
                )
            }

            Log.d(TAG, "共解析 ${sensors.size} 个传感器")
            callback(sensors)
        }
    }

    fun getDeviceSensors(deviceId: Int, apiTags: String = "", callback: (List<SensorPoint>) -> Unit) {
        val url = "$BASE_URL/Devices/$deviceId/Sensors?apiTags=$apiTags"
        Log.d(TAG, "GET $url (设备ID: $deviceId)")

        httpGetAsync(url) { resp ->
            val (_, _, resultObj) = parseApiResponse(resp)
            val resultList = resultObj as? List<*> ?: emptyList<Any>()

            val sensors = resultList.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                SensorPoint(
                    DeviceID = (map["DeviceID"] as? Number)?.toInt() ?: 0,
                    DeviceName = "",
                    ApiTag = map["ApiTag"] as? String ?: "",
                    Name = map["Name"] as? String ?: "",
                    Value = "",
                    Unit = map["Unit"] as? String ?: "",
                    At = "",
                    TransType = (map["TransType"] as? Number)?.toInt() ?: 0,
                    OperType = (map["OperType"] as? Number)?.toInt() ?: 0,
                    SensorType = map["SensorType"] as? String ?: ""
                )
            }

            Log.d(TAG, "共解析 ${sensors.size} 个传感器/执行器")
            callback(sensors)
        }
    }

    // 发送控制指令
    fun sendCommand(deviceId: Int, apiTag: String, value: String, callback: ((Boolean) -> Unit)? = null) {
        val command = when (value.trim()) {
            "1", "true", "True", "TRUE", "on", "ON" -> "1"
            "0", "false", "False", "FALSE", "off", "OFF" -> "0"
            else -> "0"
        }

        val url = "$BASE_URL/Cmds?deviceId=$deviceId&apiTag=$apiTag"
        val request = Request.Builder()
            .url(url)
            .post(command.toRequestBody("application/json".toMediaType()))
            .addHeader("AccessToken", accessToken ?: "")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "SendCommand failed: ${e.message}")
                callback?.invoke(false)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val (status) = parseApiResponse(resp.body?.string() ?: "")
                    callback?.invoke(status == 0)
                }
            }
        })
    }

    // 策略 API

    fun getStrategies(projectId: Int, callback: (List<Map<String, Any?>>) -> Unit) {
        val url = "$BASE_URL/Strategys?ProjectID=$projectId&PageSize=100&PageIndex=1"
        Log.d(TAG, "GET $url")

        httpGetAsync(url) { resp ->
            val (status, _, resultObj) = parseApiResponse(resp)
            if (status == -1) { callback(emptyList()); return@httpGetAsync }

            try {
                val resultMap = resultObj as? Map<*, *>
                val pageSet = resultMap?.get("PageSet") as? List<*> ?: emptyList<Any>()
                @Suppress("UNCHECKED_CAST")
                val strategies = pageSet.mapNotNull { it as? Map<String, Any?> }
                Log.d(TAG, "获取到 ${strategies.size} 条策略")
                callback(strategies)
            } catch (e: Exception) {
                Log.e(TAG, "getStrategies parse failed", e)
                callback(emptyList())
            }
        }
    }

    fun addStrategy(
        deviceId: Int, kind: Int, expression: String,
        variables: List<Map<String, Any>>, actions: List<Map<String, Any>>,
        runTimes: List<Map<String, Any>> = emptyList(),
        callback: (Int?) -> Unit
    ) {
        val url = "$BASE_URL/Strategys"
        val body = gson.toJson(mapOf(
            "DeviceID" to deviceId, "Kind" to kind, "Expression" to expression,
            "StrategyVariableList" to variables, "StrategyActionList" to actions,
            "StrategyRunTimeList" to runTimes
        ))
        Log.d(TAG, "POST Strategys body=$body")

        httpPostAsync(url, body) { resp ->
            val (status, msg, resultObj) = parseApiResponse(resp)
            Log.d(TAG, "addStrategy Status=$status Msg=$msg")

            if (status == 0 && resultObj is Number) {
                val id = resultObj.toInt()
                Log.d(TAG, "策略创建成功 ID=$id")
                callback(id)
            } else {
                callback(null)
            }
        }
    }

    fun addStrategyWeb(bodyMap: Map<String, Any>, callback: (Int?) -> Unit) {
        val url = "$BASE_URL/Strategys"
        val body = gson.toJson(bodyMap)
        Log.d(TAG, "POST Strategys(body) body=$body")

        httpPostAsync(url, body) { resp ->
            val (status, msg, resultObj) = parseApiResponse(resp)
            Log.d(TAG, "addStrategyWeb Status=$status Msg=$msg")

            if (status == 0 && resultObj is Number) {
                callback(resultObj.toInt())
            } else {
                callback(null)
            }
        }
    }

    fun deleteStrategy(strategyIds: List<Int>, callback: (Boolean) -> Unit) {
        val url = "$BASE_URL/Strategys"
        Log.d(TAG, "DELETE Strategys body=$strategyIds")

        httpDeleteAsync(url, gson.toJson(strategyIds)) { resp ->
            val (status) = parseApiResponse(resp)
            callback(status == 0)
        }
    }

    fun enableStrategy(strategyId: Int, enable: Boolean, callback: (Boolean) -> Unit) {
        val url = "$BASE_URL/Strategys/Enable/$strategyId?enable=$enable"
        Log.d(TAG, "POST $url")

        httpPostAsync(url, "") { resp ->
            val (status) = parseApiResponse(resp)
            callback(status == 0)
        }
    }

    /**
     * 创建传感器/执行器
     * @param deviceId 设备ID
     * @param apiTag 传感器标识（如 "temp", "humi", "light" 等）
     * @param name 传感器名称
     * @param unit 单位（如 "°C", "%RH", "lux"）
     * @param transType 传输类型：0=传感器，1=执行器
     * @param operType 操作类型：0=只读，1=可控，3=开关，4=数值调节
     * @param sensorType 数据类型：int=整数，float=浮点数，string=字符串，bool=布尔
     * @return 创建成功返回SensorPoint，失败返回null
     */
    fun createSensor(
        deviceId: Int,
        apiTag: String,
        name: String,
        unit: String,
        transType: Int = 0,
        operType: Int = 0,
        sensorType: String = "float",
        initValue: String? = null,
        callback: (SensorPoint?) -> Unit
    ) {
        val url = "$BASE_URL/Devices/$deviceId/Sensors"

        // sensorType 字符串 → DataType 字节（API 要求：0=int, 1=float, 2=bool, 3=string）
        val dataType = when (sensorType) {
            "int" -> 0
            "float" -> 1
            "bool" -> 2
            "string" -> 3
            else -> 1
        }

        fun buildBody(sensorName: String): String {
            val bodyMap = mutableMapOf<String, Any>(
                "ApiTag" to apiTag,
                "Name" to sensorName,
                "TransType" to transType,
                "DataType" to dataType
            )
            if (unit.isNotBlank()) {
                bodyMap["Unit"] = unit
            }
            if (transType == 1) {
                bodyMap["OperType"] = operType
            }
            if (initValue != null) {
                bodyMap["Value"] = initValue
            }
            return gson.toJson(bodyMap)
        }

        fun buildResult(sensorName: String, id: Int): SensorPoint {
            return SensorPoint(
                DeviceID = deviceId,
                DeviceName = "",
                ApiTag = apiTag,
                Name = sensorName,
                Value = "--",
                Unit = unit,
                TransType = transType,
                OperType = operType,
                SensorType = sensorType
            )
        }

        // 先用原始 name 尝试，若 API 拒绝中文名则用 apiTag 重试
        val body = buildBody(name)
        Log.d(TAG, "POST $url body=$body")

        httpPostAsync(url, body) { resp ->
            val (status, msg, resultObj) = parseApiResponse(resp)
            Log.d(TAG, "createSensor Status=$status Msg=$msg ResultObj=$resultObj")

            if (status == 0 && resultObj is Number) {
                Log.d(TAG, "传感器创建成功: $apiTag (ID=${resultObj.toInt()})")
                callback(buildResult(name, resultObj.toInt()))
            } else if (name != apiTag && msg.contains("Name")) {
                // 中文名被拒，用 apiTag 作为名称重试
                Log.d(TAG, "Name被拒，用apiTag重试: $apiTag")
                val retryBody = buildBody(apiTag)
                httpPostAsync(url, retryBody) { resp2 ->
                    val (status2, msg2, resultObj2) = parseApiResponse(resp2)
                    if (status2 == 0 && resultObj2 is Number) {
                        Log.d(TAG, "传感器创建成功(重试): $apiTag (ID=${resultObj2.toInt()})")
                        callback(buildResult(apiTag, resultObj2.toInt()))
                    } else {
                        Log.e(TAG, "传感器创建失败: Status=$status2 Msg=$msg2")
                        callback(null)
                    }
                }
            } else {
                Log.e(TAG, "传感器创建失败: Status=$status Msg=$msg")
                callback(null)
            }
        }
    }

    /**
     * 删除传感器
     * @param deviceId 设备ID
     * @param apiTag 传感器标识
     * @return 是否删除成功
     */
    fun deleteSensor(deviceId: Int, apiTag: String, callback: (Boolean) -> Unit) {
        val url = "$BASE_URL/Devices/$deviceId/Sensors/$apiTag"
        Log.d(TAG, "DELETE $url")

        httpDeleteAsync(url) { resp ->
            val (status) = parseApiResponse(resp)
            callback(status == 0)
        }
    }

    fun updateStrategy(
        strategyId: Int, deviceId: Int, kind: Int, expression: String,
        variables: List<Map<String, Any>>, actions: List<Map<String, Any>>,
        callback: (Boolean) -> Unit
    ) {
        val url = "$BASE_URL/Strategys/$strategyId"
        val body = gson.toJson(mapOf(
            "DeviceID" to deviceId, "Kind" to kind, "Expression" to expression,
            "StrategyVariableList" to variables, "StrategyActionList" to actions,
            "StrategyRunTimeList" to emptyList<Any>()
        ))
        Log.d(TAG, "PUT Strategys/$strategyId body=$body")

        httpPutAsync(url, body) { resp ->
            val (status) = parseApiResponse(resp)
            callback(status == 0)
        }
    }

    // 状态
    fun isConnected(): Boolean = accessToken != null
    fun getAccessToken(): String? = accessToken
    fun setAccessToken(token: String) { accessToken = token }
    fun disconnect() { accessToken = null }

    fun clearPrefs() {
        prefs.edit().clear().apply()
    }
}
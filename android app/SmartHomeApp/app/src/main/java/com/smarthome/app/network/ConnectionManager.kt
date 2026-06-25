package com.smarthome.app.network

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.smarthome.app.model.Rule
import com.smarthome.app.model.SensorData
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.util.UUID

class ConnectionManager {

    companion object {
        const val TAG = "ConnMgr"
        const val MSG_CONNECTED = 1
        const val MSG_DISCONNECTED = 2
        const val MSG_SENSOR_DATA = 3
        const val MSG_CONTROL_ACK = 4
        const val MSG_ERROR = 5
        const val MSG_LOG = 6
        const val MSG_RULE_ACK = 7

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @Volatile
        var instance: ConnectionManager = ConnectionManager()
    }

    var connectionMode: String = "wifi"
        private set

    private var tcpSocket: Socket? = null
    private var tcpWriter: PrintWriter? = null
    private var tcpReader: BufferedReader? = null

    private var btSocket: BluetoothSocket? = null
    private var btInputStream: InputStream? = null
    private var btOutputStream: OutputStream? = null

    var isConnected: Boolean = false
        private set

    private var handler: Handler? = null
    private val gson = Gson()

    fun setHandler(h: Handler) {
        handler = h
    }

    // ==================== WiFi ====================

    fun connectWifi(host: String, port: Int) {
        disconnect()
        connectionMode = "wifi"

        Thread {
            try {
                sendLog("WiFi 正在连接 $host:$port ...")
                tcpSocket = Socket(host, port)
                tcpSocket!!.keepAlive = true

                tcpReader = BufferedReader(
                    InputStreamReader(tcpSocket!!.getInputStream(), Charsets.UTF_8)
                )
                tcpWriter = PrintWriter(
                    OutputStreamWriter(tcpSocket!!.getOutputStream(), Charsets.UTF_8), true
                )

                isConnected = true
                sendMsg(MSG_CONNECTED, "WiFi 已连接")

                listenTcp()
            } catch (e: Exception) {
                Log.e(TAG, "WiFi连接失败", e)
                sendMsg(MSG_ERROR, "WiFi 连接失败: ${e.message}")
                disconnect()
            }
        }.start()
    }

    private fun listenTcp() {
        try {
            val reader = tcpReader ?: return
            while (isConnected) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    processReceivedData(line.trim())
                }
            }
        } catch (e: Exception) {
            if (isConnected) {
                sendMsg(MSG_ERROR, "连接断开: ${e.message}")
            }
        } finally {
            isConnected = false
            sendMsg(MSG_DISCONNECTED, "已断开")
        }
    }

    // ==================== 蓝牙 ====================

    fun connectBluetooth(device: BluetoothDevice) {
        disconnect()
        connectionMode = "bluetooth"

        Thread {
            try {
                sendLog("蓝牙正在连接 ${device.name ?: device.address} ...")
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket!!.connect()

                btInputStream = btSocket!!.inputStream
                btOutputStream = btSocket!!.outputStream

                isConnected = true
                sendMsg(MSG_CONNECTED, "蓝牙已连接: ${device.name}")

                listenBluetooth()
            } catch (e: Exception) {
                Log.e(TAG, "蓝牙连接失败", e)
                sendMsg(MSG_ERROR, "蓝牙连接失败: ${e.message}")
                disconnect()
            }
        }.start()
    }

    private fun listenBluetooth() {
        try {
            val input = btInputStream ?: return
            val buffer = ByteArray(1024)
            val sb = StringBuilder()

            while (isConnected) {
                val bytes = input.read(buffer)
                if (bytes > 0) {
                    sb.append(String(buffer, 0, bytes, Charsets.UTF_8))
                    while (sb.indexOf("\n") >= 0) {
                        val idx = sb.indexOf("\n")
                        val line = sb.substring(0, idx).trim()
                        sb.delete(0, idx + 1)
                        if (line.isNotBlank()) {
                            processReceivedData(line)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isConnected) {
                sendMsg(MSG_ERROR, "蓝牙断开: ${e.message}")
            }
        } finally {
            isConnected = false
            sendMsg(MSG_DISCONNECTED, "蓝牙已断开")
        }
    }

    // ==================== 数据处理 ====================

    private fun processReceivedData(json: String) {
        Log.d(TAG, "收到: $json")
        try {
            val map = gson.fromJson(json, Map::class.java)
            when (map["type"] as? String) {
                "sensor" -> {
                    val data = SensorData(
                        node = map["node"] as? String ?: "",
                        temp = (map["temp"] as? Number)?.toFloat() ?: 0f,
                        humi = (map["humi"] as? Number)?.toFloat() ?: 0f,
                        light = (map["light"] as? Number)?.toInt() ?: 0,
                        pir = (map["pir"] as? Number)?.toInt() ?: 0,
                        timestamp = map["timestamp"] as? String ?: ""
                    )
                    sendMsg(MSG_SENSOR_DATA, data)
                }
                "control" -> {
                    sendMsg(MSG_CONTROL_ACK, json)
                }
                "rule_ack" -> {
                    sendMsg(MSG_RULE_ACK, json)
                }
            }
        } catch (_: JsonSyntaxException) {}
    }

    // ==================== 发送 ====================

    fun sendCommand(device: String, action: String) {
        val nodeAddr = when (device) {
            "light" -> "0x0004"
            "fan" -> "0x0005"
            else -> "0x0000"
        }
        val json = """{"type":"control","node":"$nodeAddr","device":"$device","action":"$action"}"""
        sendRaw(json)
    }

    fun sendRule(rule: com.smarthome.app.model.Rule) {
        val json = gson.toJson(
            mapOf(
                "type" to "rule",
                "rule_id" to rule.id,
                "name" to rule.name,
                "sensorType" to rule.sensorType,
                "operator" to rule.operator,
                "threshold" to rule.threshold,
                "actionNode" to rule.actionNode,
                "actionDevice" to rule.actionDevice,
                "actionCmd" to rule.actionCmd,
                "enabled" to rule.enabled
            )
        )
        sendRaw(json)
    }

    fun sendRawCommand(json: String) {
        sendRaw(json)
    }

    private fun sendRaw(data: String) {
        Thread {
            try {
                when (connectionMode) {
                    "wifi" -> {
                        tcpWriter?.println(data)
                        tcpWriter?.flush()
                    }
                    "bluetooth" -> {
                        btOutputStream?.write((data + "\n").toByteArray(Charsets.UTF_8))
                        btOutputStream?.flush()
                    }
                }
                sendLog("已发送: $data")
            } catch (e: Exception) {
                sendMsg(MSG_ERROR, "发送失败: ${e.message}")
            }
        }.start()
    }

    // ==================== 断开 ====================

    fun disconnect() {
        isConnected = false
        try { tcpSocket?.close() } catch (_: Exception) {}
        try { tcpWriter?.close() } catch (_: Exception) {}
        try { tcpReader?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        try { btInputStream?.close() } catch (_: Exception) {}
        try { btOutputStream?.close() } catch (_: Exception) {}
        tcpSocket = null; tcpWriter = null; tcpReader = null
        btSocket = null; btInputStream = null; btOutputStream = null
    }

    // ==================== 辅助 ====================

    private fun sendMsg(what: Int, obj: Any) {
        val h = handler ?: return
        h.sendMessage(h.obtainMessage(what, obj))
    }

    private fun sendLog(msg: String) {
        Log.d(TAG, msg)
        sendMsg(MSG_LOG, msg)
    }
}
package com.smarthome.app.cloud

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.Timer
import java.util.TimerTask

/**
 * NleCloud TCP 通信管理器
 * 用于 Android App 与云平台之间的 TCP 长连接，实现快速命令下发
 */
class NleCloudTcpManager {

    companion object {
        private const val TAG = "NleTcp"
        private const val TCP_HOST = "ndp.nlecloud.com"
        private const val TCP_PORT = 8600
        private const val HEARTBEAT_INTERVAL = 50000L // 心跳间隔50秒
        private const val RECONNECT_INTERVAL = 5000L  // 重连间隔5秒
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null
    private var heartbeatTimer: Timer? = null
    private var receiveThread: Thread? = null

    private var deviceTag: String = ""
    private var deviceKey: String = ""
    private var isConnected = false

    private var onCommandReceived: ((String) -> Unit)? = null
    private var onConnectionChanged: ((Boolean, String) -> Unit)? = null

    /**
     * 设置回调
     */
    fun setOnCommandListener(listener: (String) -> Unit) {
        onCommandReceived = listener
    }

    fun setOnConnectionListener(listener: (Boolean, String) -> Unit) {
        onConnectionChanged = listener
    }

    /**
     * 连接到云平台 TCP 服务器
     */
    fun connect(tag: String, key: String) {
        deviceTag = tag
        deviceKey = key

        Thread {
            try {
                Log.d(TAG, "正在连接 $TCP_HOST:$TCP_PORT")
                socket = Socket(TCP_HOST, TCP_PORT)
                outputStream = socket?.getOutputStream()
                reader = BufferedReader(InputStreamReader(socket?.getInputStream()))

                // 发送登录报文
                val loginJson = "{\"t\":1,\"device\":\"$tag\",\"key\":\"$key\"}"
                sendRaw(loginJson)
                Log.d(TAG, "登录报文: $loginJson")

                isConnected = true
                onConnectionChanged?.invoke(true, "TCP 已连接")

                // 启动心跳
                startHeartbeat()

                // 启动接收线程
                startReceiveThread()

            } catch (e: Exception) {
                Log.e(TAG, "TCP 连接失败: ${e.message}")
                isConnected = false
                onConnectionChanged?.invoke(false, "TCP 连接失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 发送控制命令到云平台
     * @param apiTag 传感器标识名（如 "light"、"fan"）
     * @param data 命令值（1=开，0=关）
     */
    fun sendCommand(apiTag: String, data: Int) {
        if (!isConnected) {
            Log.w(TAG, "TCP 未连接，无法发送命令")
            return
        }

        Thread {
            try {
                val cmdJson = "{\"t\":5,\"apitag\":\"$apiTag\",\"data\":$data}"
                sendRaw(cmdJson)
                Log.d(TAG, "发送命令: $cmdJson")
            } catch (e: Exception) {
                Log.e(TAG, "发送命令失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 发送原始字符串
     */
    private fun sendRaw(data: String) {
        outputStream?.let { os ->
            os.write((data + "\n").toByteArray())
            os.flush()
            Log.d(TAG, "发送: $data")
        }
    }

    /**
     * 启动心跳定时器
     */
    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (isConnected) {
                        try {
                            sendRaw("$#AT#")
                            Log.d(TAG, "发送心跳")
                        } catch (e: Exception) {
                            Log.e(TAG, "心跳发送失败: ${e.message}")
                            disconnect()
                        }
                    }
                }
            }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL)
        }
    }

    /**
     * 启动接收线程
     */
    private fun startReceiveThread() {
        receiveThread = Thread {
            try {
                while (isConnected) {
                    val line = reader?.readLine() ?: break
                    if (line.isNotBlank()) {
                        Log.d(TAG, "收到: $line")
                        onCommandReceived?.invoke(line)
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Log.e(TAG, "接收异常: ${e.message}")
                    disconnect()
                }
            }
        }
        receiveThread?.start()
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isConnected = false
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        receiveThread?.interrupt()
        receiveThread = null

        try {
            outputStream?.close()
            reader?.close()
            socket?.close()
        } catch (_: Exception) {}

        outputStream = null
        reader = null
        socket = null

        onConnectionChanged?.invoke(false, "TCP 已断开")
        Log.d(TAG, "TCP 已断开")
    }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = isConnected
}

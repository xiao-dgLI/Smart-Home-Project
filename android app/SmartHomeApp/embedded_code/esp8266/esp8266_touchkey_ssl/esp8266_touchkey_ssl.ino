/**
 * ESP8266 (ESP-01S) 触摸按键云平台上报 (TCP + SSL/TLS 加密)
 * 
 * 功能：
 * 1. 连接 WiFi 和 NleCloud 云平台 (SSL/TLS 加密 TCP)
 * 2. 通过硬件串口接收 CH32X203C 的触摸按键状态和数值
 * 3. 将触摸状态上报到云平台 (touchkey1, touchkey2)
 * 
 * 硬件连接 (ESP-01S):
 * - TX (GPIO1): 连接 CH32X203C USART3_RX (PB11)
 * - RX (GPIO0): 连接 CH32X203C USART3_TX (PB10)
 * - 注意: ESP-01S 没有 D5/D6/D7/D8，直接用硬件串口
 * 
 * 依赖库：
 * - ESP8266WiFi (内置)
 * - ESP8266WiFiClientSecure (内置)
 */

#include <ESP8266WiFi.h>
#include <WiFiClientSecure.h>
#include <WiFiUdp.h>

/*======================== 配置区域 ========================*/

// WiFi 配置
const char* WIFI_SSID = "REDMIBOOK16";
const char* WIFI_PASSWORD = "00008888";

// 云平台 TCP 配置 (SSL/TLS 加密)
#define TCP_SERVER    "ndp.nlecloud.com"
#define TCP_PORT      8601  // 8601 = SSL/TLS 加密端口

// 设备信息
#define DEVICE_TAG    "touchkey"                           // 设备标识
#define SECRET_KEY    "de751521d00a4abcbfbf858993bfeabb"  // 传输密钥

// 心跳间隔 (毫秒)
#define HEARTBEAT_INTERVAL 50000

// UDP 日志广播配置 (用于电脑端监听)
#define LOG_UDP_PORT 4210
#define LOG_BROADCAST_IP "255.255.255.255"

/*==========================================================*/

// ESP-01S 使用硬件串口连接 CH32X203C
// Serial = 硬件串口 (TX=GPIO1, RX=GPIO3)
// 调试信息通过 UDP 广播查看，不再占用串口

// SSL/TLS 客户端
WiFiClientSecure sslClient;
bool tcpConnected = false;
unsigned long lastHeartbeat = 0;

// UDP 日志广播
WiFiUDP udpLog;

// 触摸值和状态
uint16_t touch1_value = 0;
uint16_t touch2_value = 0;

/**
 * @brief 连接 WiFi
 */
void connectWiFi() {
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    
    int retry = 0;
    while (WiFi.status() != WL_CONNECTED && retry < 30) {
        delay(500);
        retry++;
    }
    
    if (WiFi.status() == WL_CONNECTED) {
        logUDP("[WiFi] Connected, IP: " + WiFi.localIP().toString());
    } else {
        logUDP("[WiFi] Failed, restarting...");
        ESP.restart();
    }
}

/**
 * @brief 连接 TCP 云平台 (SSL/TLS)
 */
void connectTCP() {
    if (tcpConnected && sslClient.connected()) {
        return;
    }
    
    logUDP("[SSL] Connecting to " + String(TCP_SERVER) + ":" + String(TCP_PORT));
    
    // SSL/TLS 配置
    sslClient.setInsecure();
    
    if (sslClient.connect(TCP_SERVER, TCP_PORT)) {
        logUDP("[SSL] Connected!");
        
        // 发送登录报文 (协议 v1.1)
        String loginJson = "{\"t\":1,\"device\":\"" + String(DEVICE_TAG) + "\",\"key\":\"" + String(SECRET_KEY) + "\",\"ver\":\"v1.1\"}";
        tcpSend(loginJson);
        
        tcpConnected = true;
        lastHeartbeat = millis();
        
        // 连接成功后立即上报一次
        publishTouchData();
    } else {
        logUDP("[SSL] Connection failed!");
        tcpConnected = false;
    }
}

/**
 * @brief 通过 UDP 广播日志到电脑
 */
void logUDP(String msg) {
    udpLog.beginPacket(LOG_BROADCAST_IP, LOG_UDP_PORT);
    udpLog.print(msg);
    udpLog.endPacket();
}

/**
 * @brief 发送数据到 TCP 服务器
 */
void tcpSend(String data) {
    if (sslClient.connected()) {
        sslClient.println(data);
        logUDP("[TX] " + data);
    }
}

/**
 * @brief 接收 TCP 服务器数据
 */
void tcpReceive() {
    while (sslClient.available()) {
        String line = sslClient.readStringUntil('\n');
        line.trim();
        if (line.length() > 0) {
            logUDP("[RX] " + line);
            
            // 处理心跳回复
            if (line.indexOf("$#AT#") >= 0) {
                logUDP("[HEARTBEAT] ACK received");
            }
        }
    }
}

/**
 * @brief 发送触摸数据到云平台
 * 上行格式: {"t":3,"datatype":1,"datas":{"touchkey1":"0","touchkey2":"1"},"msgid":1}
 * t:3 = 上行数据, datatype:1 = JSON对象, datas = 传感器数据
 */
void publishTouchData() {
    if (!tcpConnected || !sslClient.connected()) {
        return;
    }
    
    static int msgId = 1;
    
    String jsonData = "{\"t\":3,\"datatype\":1,\"datas\":{";
    jsonData += "\"touchkey1\":\"" + String(touch1_value) + "\",";
    jsonData += "\"touchkey2\":\"" + String(touch2_value) + "\"";
    jsonData += "},\"msgid\":";
    jsonData += String(msgId++);
    jsonData += "}";
    
    tcpSend(jsonData);
}

/**
 * @brief 发送心跳
 */
void sendHeartbeat() {
    if (tcpConnected && sslClient.connected()) {
        tcpSend("$#AT#");
    }
}

/**
 * @brief 解析从 CH32X203C 接收的数据
 * 格式: V1:<value1>,V2:<value2>,S1:<0/1>,S2:<0/1>
 */
void parseTouchData(String data) {
    int idxV1 = data.indexOf("V1:");
    int idxV2 = data.indexOf(",V2:");
    int idxS1 = data.indexOf(",S1:");
    int idxS2 = data.indexOf(",S2:");
    
    if (idxV1 >= 0 && idxV2 > idxV1 && idxS1 > idxV2 && idxS2 > idxS1) {
        String val1 = data.substring(idxV1 + 3, idxV2);
        String val2 = data.substring(idxV2 + 4, idxS1);

        val1.trim();
        val2.trim();
        
        touch1_value = val1.toInt();
        touch2_value = val2.toInt();

        // 每次收到数据都上报到云平台
        logUDP("[UART] " + data);
        publishTouchData();
    }
}
}

/**
 * @brief 初始化
 */
void setup() {
    // 初始化硬件串口 (连接 CH32X203C, 不用于USB调试)
    Serial.begin(4800);
    
    // 连接 WiFi
    connectWiFi();

    // 启动 UDP 日志广播 (调试信息通过UDP查看)
    udpLog.begin(LOG_UDP_PORT);
    logUDP("[SYSTEM] ESP8266 (ESP-01S) TouchKey Gateway (SSL) started");
    logUDP("[CONFIG] HW Serial=4800 TX(GPIO1)->PB11 RX(GPIO0)->PB10");

    // 连接 TCP
    connectTCP();
}

/**
 * @brief 主循环
 */
void loop() {
    // 维持 WiFi 连接
    if (WiFi.status() != WL_CONNECTED) {
        connectWiFi();
    }
    
    // 维持 SSL/TLS 连接
    if (!tcpConnected || !sslClient.connected()) {
        tcpConnected = false;
        connectTCP();
    }
    
    // 接收 TCP 数据
    tcpReceive();
    
    // 心跳维持
    if (tcpConnected && sslClient.connected()) {
        if (millis() - lastHeartbeat >= HEARTBEAT_INTERVAL) {
            sendHeartbeat();
            lastHeartbeat = millis();
        }
    }
    
    // 读取 CH32X203C 发送的数据 (通过硬件串口)
    static String rxBuffer = "";
    static unsigned long lastRxTime = 0;

    while (Serial.available()) {
        char c = Serial.read();
        lastRxTime = millis();

        if (c == '\n' || c == '\r') {
            if (rxBuffer.length() > 0) {
                logUDP("[UART RAW] " + rxBuffer);
                parseTouchData(rxBuffer);
                rxBuffer = "";
            }
        } else {
            rxBuffer += c;

            // 防止缓冲区溢出
            if (rxBuffer.length() > 64) {
                rxBuffer = "";
            }
        }
    }

    // 每10秒打印一次调试信息
    static unsigned long lastDebugTime = 0;
    if (millis() - lastDebugTime >= 10000) {
        lastDebugTime = millis();
        logUDP("[DEBUG] HW Serial available=" + String(Serial.available()) +
               " rxBuffer='" + rxBuffer + "'" +
               " lastRx=" + String(millis() - lastRxTime) + "ms ago");
    }
}

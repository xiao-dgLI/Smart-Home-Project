/**
 * ESP8266 (ESP-01S) 触摸按键云平台上报 (TCP 协议)
 * 
 * 硬件连接 (ESP-01S):
 * - TX (GPIO1): 连接 CH32X203C USART3_RX (PB11)
 * - RX (GPIO0): 连接 CH32X203C USART3_TX (PB10)
 */

#include <ESP8266WiFi.h>
#include <WiFiUdp.h>

const char* WIFI_SSID = "REDMIBOOK16";
const char* WIFI_PASSWORD = "00008888";

#define TCP_SERVER    "ndp.nlecloud.com"
#define TCP_PORT      8600
#define DEVICE_TAG    "touchkey"
#define SECRET_KEY    "de751521d00a4abcbfbf858993bfeabb"
#define HEARTBEAT_INTERVAL 50000
#define LOG_UDP_PORT 4210
#define LOG_BROADCAST_IP "255.255.255.255"

WiFiClient tcpClient;
bool tcpConnected = false;
unsigned long lastHeartbeat = 0;
WiFiUDP udpLog;

uint16_t v1_value = 0;
uint16_t v2_value = 0;

void logUDP(String msg) {
    udpLog.beginPacket(LOG_BROADCAST_IP, LOG_UDP_PORT);
    udpLog.print(msg);
    udpLog.endPacket();
}

void connectWiFi() {
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    int retry = 0;
    while (WiFi.status() != WL_CONNECTED && retry < 30) {
        delay(500);
        retry++;
    }
    if (WiFi.status() == WL_CONNECTED) {
        logUDP("[WiFi] OK IP=" + WiFi.localIP().toString());
    } else {
        ESP.restart();
    }
}

void tcpSend(String data) {
    if (tcpClient.connected()) {
        tcpClient.println(data);
        logUDP("[TX] " + data);
    }
}

void connectTCP() {
    if (tcpConnected && tcpClient.connected()) return;
    logUDP("[TCP] connecting...");
    if (tcpClient.connect(TCP_SERVER, TCP_PORT)) {
        String login = "{\"t\":1,\"device\":\"" + String(DEVICE_TAG) + "\",\"key\":\"" + String(SECRET_KEY) + "\",\"ver\":\"v1.1\"}";
        tcpSend(login);
        tcpConnected = true;
        lastHeartbeat = millis();
    } else {
        logUDP("[TCP] failed");
        tcpConnected = false;
    }
}

void tcpReceive() {
    while (tcpClient.available()) {
        String line = tcpClient.readStringUntil('\n');
        line.trim();
        if (line.length() > 0) logUDP("[RX] " + line);
    }
}

void publishTouchData() {
    if (!tcpConnected || !tcpClient.connected()) return;
    static int msgId = 1;
    String json = "{\"t\":3,\"datatype\":1,\"datas\":{";
    json += "\"touchkey1\":\"" + String(v1_value) + "\",";
    json += "\"touchkey2\":\"" + String(v2_value) + "\"";
    json += "},\"msgid\":" + String(msgId++) + "}";
    tcpSend(json);
}

void parseTouchData(String data) {
    int i1 = data.indexOf("V1:");
    int i2 = data.indexOf(",V2:");
    if (i1 >= 0 && i2 > i1) {
        v1_value = data.substring(i1 + 3, i2).toInt();
        v2_value = data.substring(i2 + 4).toInt();
        logUDP("[PARSE] v1=" + String(v1_value) + " v2=" + String(v2_value));
        publishTouchData();
    }
}

void setup() {
    Serial.begin(4800);
    connectWiFi();
    udpLog.begin(LOG_UDP_PORT);
    logUDP("[SYSTEM] started");
    connectTCP();
}

void loop() {
    if (WiFi.status() != WL_CONNECTED) connectWiFi();
    if (!tcpConnected || !tcpClient.connected()) { tcpConnected = false; connectTCP(); }
    tcpReceive();
    if (tcpConnected && tcpClient.connected() && millis() - lastHeartbeat >= HEARTBEAT_INTERVAL) {
        tcpSend("$#AT#");
        lastHeartbeat = millis();
    }

    static String buf = "";
    while (Serial.available()) {
        char c = Serial.read();
        if (c == '\n' || c == '\r') {
            if (buf.length() > 0) { parseTouchData(buf); buf = ""; }
        } else {
            buf += c;
            if (buf.length() > 64) buf = "";
        }
    }
    delay(10);
}

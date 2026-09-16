# 触摸按键云平台上报系统

## 系统架构

```
CH32X203C (触摸检测)  <---UART--->  ESP8266 (WiFi网关)  <---TCP--->  NleCloud云平台
        |                                |                                |
   PA0: touchkey1                  D5: RX (接收数据)              t:3 上报数据
   PA1: touchkey2                  D6: TX (备用)               {"t":3,"datatype":1,"datas":{...},"msgid":N}
   PA9: UART_TX                   WiFi连接
   PA10: UART_RX                  TCP连接
   本地串口: 详细触摸值
```

## 云平台配置信息

| 参数 | 值 |
|------|-----|
| TCP服务器 | ndp.nlecloud.com |
| 端口 | 8600 |
| 设备标识 | touchkey |
| 传输密钥 | de751521d00a4abcbfbf858993bfeabb |
| 协议 | TCP |

## TCP 协议说明

### 登录报文
```json
{"t":1,"device":"touchkey","key":"de751521d00a4abcbfbf858993bfeabb"}
```

### 上报数据
```json
{"t":3,"datatype":1,"datas":{"touchkey1":"0","touchkey2":"1"},"msgid":1}
```

### 心跳
```
$#AT#
```
每50秒发送一次，服务器回复相同内容表示在线。

## 传感器标识符

| 标识符 | 说明 | 数据类型 | 值 |
|--------|------|----------|-----|
| touchkey1 | 触摸按键1状态 | 数字 | 0=未触摸, 1=触摸 |
| touchkey2 | 触摸按键2状态 | 数字 | 0=未触摸, 1=触摸 |

## 数据通信格式

### CH32X203C → ESP8266 (UART 115200)
```
V1:<触摸值1>,V2:<触摸值2>,S1:<状态1>,S2:<状态2>
```
示例：`V1:256,V2:890,S1:0,S2:1`
- V1/V2: 原始触摸值 (未触摸时较小，触摸时变大)
- S1/S2: 触摸状态 (0=未触摸, 1=触摸)

### ESP8266 → 云平台 (TCP)
```json
{"t":3,"datatype":1,"datas":{"touchkey1":"0","touchkey2":"1"},"msgid":1}
```

## 使用步骤

### 1. CH32X203C 开发板

**开发环境：** MounRiver Studio

**硬件连接：**
- PA0 → 触摸按键1
- PA1 → 触摸按键2
- PA9 (UART1_TX) → ESP8266 D5 (GPIO14)
- PA10 (UART1_RX) → ESP8266 D6 (GPIO12)

**本地调试串口：** PA9/PA10 (115200) 会打印详细的触摸值

**烧录步骤：**
1. 打开 `ch32x203c/touchkey_main.c`
2. 根据实际触摸值调整 `TOUCH_THRESHOLD` 阈值
3. 编译并烧录到 CH32X203C
4. 通过串口监视器观察触摸值变化

### 2. ESP8266 开发板

**开发环境：** Arduino IDE

**安装依赖库：**
1. 打开 Arduino IDE
2. 工具 → 管理库 → 搜索并安装：
   - ESP8266WiFi (内置，无需额外安装)

**配置步骤：**
1. 打开 `esp8266/esp8266_touchkey.ino`
2. 修改 WiFi 配置：
   ```cpp
   const char* WIFI_SSID = "你的WiFi名称";
   const char* WIFI_PASSWORD = "你的WiFi密码";
   ```
3. 选择开发板：Tools → Board → ESP8266 Boards → NodeMCU 1.0
4. 上传代码

### 3. 在云平台添加传感器

1. 登录 NleCloud 云平台
2. 进入设备管理 → 传感器管理
3. 添加两个传感器：
   - 标识符: `touchkey1`，数据类型: 数字(int)
   - 标识符: `touchkey2`，数据类型: 数字(int)

## 触摸值调试

### CH32X203C 本地串口输出
```
========================================
CH32X203C TouchKey System Started
SystemClk:48000000
ChipID:00000000
========================================
TouchKey Initialized
Threshold: 400
----------------------------------------
TK1: 128 (released) | TK2: 256 (released)
TK1: 125 (released) | TK2: 260 (released)
TK1: 890 (PRESSED)  | TK2: 258 (released)  <- 触摸按键1
TK1: 912 (PRESSED)  | TK2: 254 (released)
TK1: 130 (released) | TK2: 259 (released)  <- 松开
```

### 调整触摸阈值
根据实际触摸值调整 `TOUCH_THRESHOLD`：
- 未触摸时的典型值: 100-300
- 触摸时的典型值: 500-2000+
- 建议阈值: 取中间值，如 400

## 注意事项

1. **触摸阈值调整：** 不同的开发板和触摸电容需要不同的阈值，务必先通过串口观察实际触摸值

2. **TKey 外设：** CH32X203C 使用专用的触摸感应外设(TKey)，不是普通ADC，需要通过 `TKey1->CTLR1` 启用

3. **充电/放电时间：** 参考官方示例设置为 `CHARGE_TIME=0x10`, `DISCHARGE_TIME=0x8`

4. **状态变化上报：** 只有触摸状态发生变化时才会上报数据到云平台

5. **断线重连：** ESP8266 会自动重连 WiFi 和 TCP 服务器

6. **心跳维持：** 每50秒发送一次心跳 `$#AT#`，保持设备在线状态

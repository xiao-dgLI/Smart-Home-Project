# Smart-Home-Project

## 1. 系统架构

### 1.1 整体架构图
```
┌─────────────────┐     HTTP/OkHttp     ┌──────────────┐     TCP      ┌────────────┐     UART     ┌────────────┐
│  Android App    │ ──────────────────→ │  NleCloud    │ ──────────→ │  ESP8266   │ ──────────→ │   CC2530   │
│  (手机控制端)    │     ~100ms          │  (云平台)     │    ~500ms    │  (WiFi网关) │    ~10ms     │  (传感器)   │
│                 │ ←────────────────── │              │ ←────────── │            │ ←────────── │            │
└─────────────────┘   传感器数据轮询     └──────────────┘   TCP推送     └────────────┘   UART     └────────────┘
```

### 1.2 组件说明
| 组件 | 角色 | 协议 | 功能 |
|------|------|------|------|
| Android App | 控制端 | HTTP API | 查看数据、发送命令 |
| NleCloud | 中转平台 | HTTP + TCP | 数据存储、命令转发 |
| ESP8266 | 网关 | TCP + UART | 数据上报、命令转发 |
| CC2530 | 传感器 | UART | 数据采集、执行控制 |

---

## 2. Android App

### 2.1 技术栈
- 语言：Kotlin
- 最低版本：Android 8.0 (API 26)
- 网络：OkHttp 4.12
- 架构：单 Activity + 5 Fragment

### 2.2 功能模块
| 模块 | 页面 | 功能 |
|------|------|------|
| 仪表盘 | DashboardFragment | 传感器数据卡片、单位显示 |
| 设备控制 | ControlFragment | 执行器开关控制 |
| 策略管理 | RuleFragment | 条件任务、定时任务 |
| 通知设置 | NotificationFragment | 告警通知配置 |
| 系统设置 | SettingsFragment | 登录、项目选择 |

### 2.3 核心代码
| 文件 | 功能 |
|------|------|
| MainViewModel.kt | 业务逻辑核心|
| NleCloudManager.kt | HTTP API 管理（OkHttp连接池） |
| SensorCardAdapter.kt | 传感器卡片（本地样式匹配） |
| Rule.kt | 策略数据模型 |

### 2.4 命令下发流程
```
用户点击开关
    ↓
UI 立即更新（0ms）
    ↓
OkHttp HTTP POST → api.nlecloud.com/Cmds（~100ms）
    ↓
云平台处理（~100ms）
    ↓
TCP 推送给 ESP8266（~500ms）
    ↓
ESP8266 → CC2530 → 控制设备
```

---

## 3. NleCloud 云平台

### 3.1 API 接口
| 接口 | 方法 | 功能 |
|------|------|------|
| `/Users/Login` | POST | 用户登录 |
| `/Projects` | GET | 获取项目列表 |
| `/Devices` | GET | 获取设备列表 |
| `/Devices/Datas` | GET | 获取传感器数据 |
| `/Cmds` | POST | 发送控制命令 |
| `/Strategys` | POST | 创建策略 |
| `/Strategys` | GET | 获取策略列表 |

### 3.2 设备凭证
| 字段 | 说明 | 示例 |
|------|------|------|
| Tag | 设备标识 | ESP3303 |
| DeviceID | 设备ID | 1525829 |
| SecurityKey | 传输密钥 | c67cae32... |

### 3.3 策略格式
```json
POST /Strategys
{
  "DeviceID": 设备ID,
  "Kind": 1,
  "Expression": "ApiTag > 30",
  "StrategyVariableList": [],
  "StrategyActionList": [{"ApiTag":"nl","SetValue":"1","Delay":0}],
  "StrategyRunTimeList": [{"Period":1,"Day":0}]
}
```

---

## 4. ESP8266

### 4.1 硬件
- 型号：ESP-01S
- 连接：USB 转 TTL 下载器
- 波特率：9600

### 4.2 通信协议
| 方向 | 协议 | 地址 |
|------|------|------|
| → 云平台 | TCP | ndp.nlecloud.com:8600 |
| ← CC2530 | UART | 9600 baud |

### 4.3 数据格式
| 类型 | 格式 |
|------|------|
| 登录 | `{"t":1,"device":"TAG","key":"SECRET"}` |
| 上报 | `{"t":3,"datatype":1,"datas":{...},"msgid":N}` |
| 接收命令 | `{"t":5,"apitag":"light","data":1}` |
| 心跳 | `$#AT#` |

### 4.4 串口命令
| 字节 | 功能 |
|------|------|
| 0x01 | 开灯 |
| 0x02 | 关灯 |
| 0x03 | 开风扇 |
| 0x04 | 关风扇 |

### 4.5 CC2530 数据格式
| 前缀 | 数据 | 示例 |
|------|------|------|
| T: | 温度 | T:25 |
| H: | 湿度 | H:55 |
| L: | 光照 | L:300 |
| HU: | 人体 | HU:1 |

---

## 5. CC2530

### 5.1 硬件
- 芯片：CC2530
- 传感器：温度、湿度、光照、人体检测
- 执行器：LED1/LED4（灯光）、LED2/LED3（风扇）

### 5.2 引脚定义
| 引脚 | 功能 |
|------|------|
| P1_0 | LED1（灯光） |
| P1_1 | LED2（风扇） |
| P1_2 | 按键 |
| P1_3 | LED3（风扇） |
| P1_4 | LED4（灯光） |
| P0.4 | UART1 TX → ESP8266 RX |
| P0.5 | UART1 RX ← ESP8266 TX |

### 5.3 串口配置
| 参数 | 值 |
|------|-----|
| 波特率 | 9600 |
| 数据位 | 8 |
| 停止位 | 1 |
| 校验 | 无 |

---

## 6. 数据流

### 6.1 传感器数据流（上行）
```
CC2530 采集传感器
    ↓ UART: T:25\nH:55\nL:300\nHU:0\n
ESP8266 接收并解析
    ↓ TCP: {"t":3,"datas":{"temp":25,"humi":55,...}}
云平台存储
    ↓ HTTP GET: /Devices/Datas
Android App 显示
```

### 6.2 控制命令流（下行）
```
Android App 点击开关
    ↓ HTTP POST: /Cmds?deviceId=xxx&apiTag=light  Body: "1"
云平台接收命令
    ↓ TCP: {"t":5,"apitag":"light","data":1}
ESP8266 接收并转发
    ↓ UART: 0x01
CC2530 控制 LED
```

---

## 7. 延迟分析

| 环节 | 耗时 |
|------|------|
| Android UI 更新 | 0ms |
| HTTP 请求 | ~100ms（平台限制） |
| 云平台处理 | ~100ms |
| TCP 推送到 ESP8266 | ~500ms |
| ESP8266 → CC2530 | ~10ms |
| **总计** | **~700ms** |

---

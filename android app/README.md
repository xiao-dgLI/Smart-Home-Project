# SmartHome Android App 技术文档

## 1. 项目概述

基于 NleCloud 物联网云平台的智能家居 Android 应用，通过 HTTP API 与云平台通信，ESP8266 通过 TCP 协议连接云平台，实现传感器数据采集和执行器控制。

### 1.1 功能列表
- 仪表盘：实时显示温度、湿度、光照、人体检测、火焰、可燃气
- 设备控制：灯光、风扇等执行器开关控制
- 云平台策略：条件任务、定时任务创建与管理
- 传感器单位显示：自动识别并显示 °C、%RH、lux 等单位
- 执行器状态同步：云平台状态变化实时更新到 App
- 自动登录：App 启动时自动恢复登录状态

### 1.2 技术栈
| 组件 | 技术 |
|------|------|
| 语言 | Kotlin |
| 最低版本 | Android 8.0 (API 26) |
| 架构 | 单 Activity + 5 Fragment |
| 网络 | OkHttp  |
| 图表 | Gson 2.11 |
| 蓝牙 | Android Bluetooth API |

---

## 2. 项目结构

```
app/src/main/java/com/smarthome/app/
├── MainActivity.kt              # 主 Activity，底部导航
├── MainViewModel.kt             # 核心业务逻辑
├── adapter/
│   ├── SensorCardAdapter.kt     # 传感器卡片（含本地样式匹配）
│   └── ControlAdapter.kt        # 执行器开关卡片
├── cloud/
│   ├── NleCloudManager.kt       # HTTP API 管理（OkHttp 连接池）
│   ├── SensorPoint.kt           # 传感器数据模型
│   ├── DeviceBaseInfo.kt        # 设备信息模型
│   └── ProjectInfo.kt           # 项目信息模型
├── fragment/
│   ├── DashboardFragment.kt     # 仪表盘页面
│   ├── ControlFragment.kt       # 设备控制页面
│   ├── RuleFragment.kt          # 策略管理页面
│   ├── NotificationFragment.kt  # 通知设置页面
│   └── SettingsFragment.kt      # 登录/设置页面
├── model/
│   ├── Rule.kt                  # 规则/策略数据模型
│   ├── SensorDeviceItem.kt      # 传感器设备项
│   ├── SensorData.kt            # 传感器实时数据
│   └── ControlSwitch.kt         # 控制开关模型
├── network/
│   └── ConnectionManager.kt     # 本地 ZigBee 通信
└── notification/
    └── NotificationHelper.kt    # 通知推送
```

---

## 3. 通信架构

### 3.1 整体架构
```
Android App ──HTTP/OkHttp──→ 云平台 ──TCP──→ ESP8266 ──串口──→ CC2530
   手机端        ~100ms        服务器     ~500ms    设备端     ~10ms
```

### 3.2 命令下发流程
```
用户点击开关
    ↓
UI 立即更新
    ↓
OkHttp HTTP POST（~100ms）
    ↓
云平台处理（~100ms）
    ↓
TCP 推送给 ESP8266（~500ms）
    ↓
ESP8266 串口发送给 CC2530（~10ms）
    ↓
CC2530 控制 LED
```

### 3.3 传感器数据轮询
```
Android App ──HTTP GET──→ 云平台 ──TCP──→ ESP8266 ←──串口── CC2530
   每2秒轮询          获取传感器数据      上报数据      采集数据
```

---

## 4. 核心模块

### 4.1 NleCloudManager.kt
基于 OkHttp 的 HTTP API 管理器，支持连接池复用：

| 方法 | 功能 | 延迟 |
|------|------|------|
| `login()` | 用户登录获取 AccessToken | ~200ms |
| `getProjects()` | 获取项目列表 | ~100ms |
| `getDevicesByProject()` | 获取设备列表 | ~100ms |
| `getSensors()` | 获取传感器实时数据 | ~100ms |
| `sendCommand()` | 发送执行器命令 | ~100ms |
| `addStrategy()` | 创建策略 | ~100ms |
| `getStrategies()` | 获取策略列表 | ~100ms |

### 4.2 OkHttp 连接池配置
```kotlin
private val httpClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .writeTimeout(2, TimeUnit.SECONDS)
    .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
    .build()
```

### 4.3 MainViewModel 核心方法

| 方法 | 功能 |
|------|------|
| `autoLogin()` | 启动时自动登录，恢复状态 |
| `connectToSavedProject()` | 连接已保存的项目 |
| `refreshCloudDevices()` | 刷新设备、传感器、执行器 |
| `toggleSwitchSilent()` | 开关控制|
| `pollCloudSensors()` | 轮询传感器数据（2秒间隔） |
| `updateActuatorStatesFromCloud()` | 同步执行器状态 |
| `attemptCloudRuleSync()` | 同步云策略 |

---

## 5. 云平台策略 API

### 5.1 条件任务
```json
POST /Strategys
{
  "DeviceID": 设备ID,
  "Kind":类型,
  "Expression": "{APITag} > 30",
  "StrategyVariableList": [],
  "StrategyActionList": [{"ApiTag":"nl","SetValue":"1","Delay":0}],
  "StrategyRunTimeList": [{"Period":1,"Day":0}]
}
```

### 5.2 定时任务
```json
POST /Strategys
{
  "DeviceID": 设备ID,
  "Kind": 类型,
  "Expression": "",
  "StrategyVariableList": [],
  "StrategyActionList": [{"ApiTag":"nl","SetValue":"1","Delay":0}],
  "StrategyRunTimeList": [{"Period":1,"Day":0,"Time":""}]
}
```

### 5.3 关键参数
| 参数 | 说明 | 值 |
|------|------|-----|
| DeviceID | GatewayID | “设备ID” |
| Kind | 设备控制 | 1 |
| Expression | 条件表达式 | "{APITag} > 30" |
| StrategyVariableList | 必须为空数组 | [] |
| Period | 1=每日, 2=每周, 3=每月 | 1 |
| SetValue | "1"=开, "0"=关 | "1" |

---

## 6. 数据模型

### 6.1 Rule（规则/策略）
```kotlin
data class Rule(
    var id: Long,                    // 唯一ID
    var name: String,                // 规则名称
    var operator: String,            // 运算符 > < == >= <=
    var threshold: Float,            // 阈值
    var enabled: Boolean,            // 是否启用
    var isCloud: Boolean,            // 是否云平台策略
    var isTimedTask: Boolean,        // 是否定时任务
    var cloudSensorApiTag: String,   // 传感器ApiTag
    var cloudActuatorApiTag: String, // 执行器ApiTag
    var cloudActionValue: String,    // 动作值 "1"开 "0"关
    var runTimePeriod: Int,          // 定时周期
    var runTimeSlots: List<RunTimeSlot> // 时间段
)
```

### 6.2 SensorDeviceItem
```kotlin
data class SensorDeviceItem(
    var id: Long,
    var icon: String,           // 图标
    var name: String,           // 名称
    var sensorType: String,     // 传感器类型
    var unit: String,           // 单位 °C %RH lux
    var source: String?,        // 来源 "cloud"
    var cloudDeviceId: Int?,    // 云设备ID
    var cloudApiTag: String?,   // ApiTag
    var matchedLocalType: String? // 匹配本地类型（颜色编码）
)
```

---

## 7. 传感器类型映射

| ApiTag | 类型 | 图标 | 单位 | 颜色规则 |
|--------|------|------|------|---------|
| temp/温度 | 温度 | 🌡 | °C | >35红 >28橙 其他绿 |
| humi/湿度 | 湿度 | 💧 | %RH | 蓝色 |
| light/光照 | 光照 | ☀ | lux | >500黄 其他灰 |
| pir/人体 | 人体检测 | ○ | - | 有人黑 无人灰 |
| flame/火焰 | 火焰 | 🔥 | - | 检测到红 安全绿 |
| gas/可燃气 | 可燃气 | 💨 | ppm | >=400橙 其他绿 |

---

## 8. 延迟分析

### 8.1 命令下发延迟
| 环节 | 耗时 | 说明 |
|------|------|------|
| UI 更新 | 0ms | 乐观更新 |
| HTTP 请求 | ~100ms | OkHttp 连接池 |
| 云平台处理 | ~100ms | 服务器处理 |
| TCP 推送到设备 | ~500ms | 云平台推送间隔 |
| ESP8266 → CC2530 | ~10ms | 串口通信 |
| **总计** | **~700ms** | |

### 8.2 优化措施
| 优化项 | 效果 |
|--------|------|
| OkHttp 连接池 | 后续请求跳过 TCP 握手 |
| 乐观更新 | UI 立即响应，无感知延迟 |
| HTTP 超时 2 秒 | 快速失败，不阻塞 |

---

## 9. 配置文件

### 9.1 network_security_config.xml
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">ndp.nlecloud.com</domain>
        <domain includeSubdomains="true">api.nlecloud.com</domain>
    </domain-config>
</network-security-config>
```

### 9.2 依赖
```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.google.code.gson:gson:2.11.0")
```

---

## 10. 调试

### Logcat 过滤
| Tag | 说明 |
|-----|------|
| NleCloud | HTTP API 请求 |
| MainVM | 业务逻辑 |

### 关键日志
```
登录成功 AccessToken=xxx
设备: xxxx
传感器: temp = 25.0
云命令成功: 灯光
```

*****新大陆云平台限制了app只能走Http请求，后续更换平台可添加其它协议（MQTT）

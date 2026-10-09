# Tandem 开发笔记

## MTA（OPPO/一加 互传）—— 硬件实测结论 2026

目标：让 PC 出现在 OPPO/一加原生互传面板里 → 接收文件。
`tandem/mta.py` 已实现完整接收端（BLE 广播 + GATT 0x9955 + ECDH +
AES-CTR 凭据 + 加入手机热点 + WSS + HTTPS 拉 zip 包）。

### 欧加和小米广播包结构
- ADV_IND 可连接包：`flags + uuid128(00003331-...-008123456789) + svcdata[0x012a]=<6字节ascii id>`
- SCAN_RSP：`svcdata[0x0204]=<10字节前缀如 "EEFB52FY1x"><名称补齐14字节><0x01>`
- 伴随广播（另一个 RPA 地址）：`uuid16 0x3334 + svcdata[0x3334]=<名称ascii>`
- 跨厂商通用：`0x01ff` + `0xffff` svcdata 键值。
- 小米：`0x1b1e` + `0xffff`。

### Windows
- 由于有一位开发者的蓝牙模块不具备发射能力，无法测试，需自行测试。



### 环境
- Python 3.13 x64；WinRT 3.2.1 组件已装（winrt-* 包）。
- `python -m tandem.mta --name TANDEM-PC --profile oppo` —— mta.py 里
  有针对单槽位适配器的广播轮换逻辑；PC 侧广播未验证。

## Android MTA 接收端 —— 端到端可用

`android/app/src/main/java/com/tandem/app/Mta.kt` —— MtaService（前台
服务）实现完整接收流程：BLE 广播（uuid128 0x3331 + svcdata
0x01ff + 扫描响应里 27 字节名称块 0xffff）→ GATT 0x9955（9954 可读可写
状态 JSON {state,mac,key,frequency}，9953 写凭据）→ ECDH P-256 +
AES-256-CTR → WifiP2pConfig.Builder 入网 → WSS 握手 → HTTPS 拉 zip →
`getExternalFilesDir(DOWNLOADS)/Tandem/`。

测试机器：一加 Ace 2 Pro（发送方，原生互传）→
小米 12S（接收方）。jpg + mp4 均收到。
一加凭据 JSON 的字段名/顺序与小米不同——解析器两者都兼容。

设备列表协议：扫描器要求同一条 ScanRecord 里
同时出现两段 svcdata：6 字节段（uuid 编码品牌+5GHz 标志：
0x012a=欧加系，0x1b1e=小米，0x01ff=通用/CatShare）和 27 字节段
（[8..9]=发送方 id rand16，[10..25]=名称16字节，[26]=0x01）。

### Android 构建（WSL —— Windows 版 SDK 已删）
- SDK：`~/android-sdk`（platforms android-36，build-tools 35.0.0），JDK：
  `openjdk-21-jdk-headless`（必须有 javac——只有 JRE 会过不了工具链检查）。
- 构建：`cd 项目名/android && ANDROID_HOME=~/android-sdk ./gradlew :app:assembleDebug`
- local.properties 里没有 sdk.dir（靠 ANDROID_HOME 环境变量）。
- 日志：`adb logcat -s MtaSvc:V`（tag 是 MtaSvc 不是 MTA）。
- 收到文件：`/sdcard/Android/data/com.tandem.app/files/Download/Tandem/`
  （Android 11+ 自带文件管理器看不到，需自行实现"所有文件访问权限"
  MANAGE_EXTERNAL_STORAGE）。

### 踩过的坑
- `Settings.Secure.getString("bluetooth_name")` → targetSdk>31 上
  SecurityException。改用 `BluetoothAdapter.getName()`。
- WifiP2pManager.initialize 需要 manifest 声明 `CHANGE_WIFI_STATE` +
  `CHANGE_NETWORK_STATE`；API33+ 的 P2P 需要 `NEARBY_WIFI_DEVICES`
  加 `usesPermissionFlags="neverForLocation"`。

## Android Tandem 客户端/中枢 —— 手机直连手机

App 已有完整 Tandem 客户端 + 手机中枢。文件：
`MainActivity.kt`（UI），`Hub.kt`（http/https 客户端 + 极简
ws 实现 + NSD 局域网发现 + multipart 上传），`PhoneHub.kt`
（NanoHTTPD/NanoWSD 服务端 :9787，仅 http），`SyncService.kt`
（常驻 ws 客户端前台服务），`ShareActivity.kt`（ACTION_SEND/
SEND_MULTIPLE），`Mta.kt`（互传接收）。

### 中枢 API（PhoneHub 对齐 tandem/server.py）
`/api/info /ca.crt /api/pair /api/items /api/files(+GET id) /api/text
/api/clipboard(GET|POST) /ws` + mDNS `_tandem._tcp`。二维码内容 =
`http://<局域网IP>:9787/?token=<master>`；客户端按 scheme 自动选
http/https（http 中枢必须开 `usesCleartextTraffic=true`！）。
鉴权 = 所有请求带 `?token=` 查询参数（不支持 Authorization 头）。

### NanoHTTPD 的坑（PhoneHub）
- `parseBody` 把 `application/json` 按 **latin-1** 解码 → 中文剪贴板
  到达即乱码。修复：按 content-length 从 `s.inputStream` 原始读字节，
  自己用 UTF-8 解码。urlencoded 参数不受影响（`decodePercent`→UTF8）。
- UI 回调（`onItemsChanged`、状态行）必须 post 回主线程——
  NanoHTTPD 工作线程直接碰 view → CalledFromWrongThread → 500。
- `NanoWSD` 升级：实现 `openWebSocket()` + `onMessage(frame)`（2.3.1
  里没有 onText/onBinary——别用）。`/ws` 分支在
  `isWebsocketRequested` 时转交 `super.serve(s)`。
- HTTP 的 `SOCKET_READ_TIMEOUT` 同样掐 ws 连接 → 服务端每 3 秒 ping，
  客户端自动回 pong。有部分厂商如 欧加 冻结 socket 时仍会 ~30-60 秒抖动断线——
  SyncService 每次重连后补拉 `/api/clipboard` 兜底对齐。

### 剪贴板同步 —— 实测结论（2026-10-08/09）
- Android 10+：后台**读**剪贴板 = 全机型禁止；后台**写** = 看 厂商。
- **小米 MIUI / HyperOS**：`adb shell appops set --uid com.tandem.app
  READ_CLIPBOARD allow` 可执行（shell 有权限）→ 服务的剪贴板监听
  后台也能读 → 任何 app 里复制都自动推送。写板同样没问题。
- **欧加 ColorOS 15/16**：shell 改 appops 被拒
  （`MANAGE_APP_OPS_MODES` 权限不足）。logcat 可见
  `ClipboardService: Denying clipboard access ... not in focus nor
  system service`，后台读（op29）写（op30）双双拒绝。
  1×1 可获焦悬浮窗 hack 也无法绕过。→ 发送只能前台进行；
  接收走横幅通知 → 点击拉起 app → onResume 强制写入剪贴板。
- 1×1 可获焦悬浮窗若不加 FLAG_NOT_FOCUSABLE = 抢输入法焦点
  （"无法呼叫输入法"）；现在只在 ~400ms 读取窗口内临时可获焦，但我们仍然在研究如何突破后台剪贴板读写权限。

### 保活栈（两个服务共用）
`START_STICKY` + 前台服务 + `PARTIAL_WAKE_LOCK` +
`WIFI_MODE_FULL_LOW_LATENCY` WifiLock + `onTaskRemoved`→AlarmManager
1 秒拉起 + 电池优化白名单弹窗（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）
+ `dumpsys deviceidle whitelist +com.tandem.app`（adb 可直加，等效）。
ColorOS 上划卡强杀依然会带走前台服务——只要没被"强行停止"，
onTaskRemoved 的拉起 intent 能兜住。



### 已验证功能
- 一加↔小米中枢扫码配对、文件上传→收件箱→下载→FileProvider 打开、
  剪贴板 ws 实时同步（前台）、后台接收+通知兜底、
  MTA 原生互传接收（jpg/mp4）——全部可用。

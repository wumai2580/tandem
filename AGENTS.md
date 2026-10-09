# Tandem dev notes

## MTA (OPPO/OnePlus 互传) — hardware findings 2026

Goal: PC appears in OPPO/OnePlus native 互传 share panel → receive files.
`tandem/mta.py` implements the receiver (BLE beacon + GATT 0x9955 + ECDH +
AES-CTR creds + join phone hotspot + WSS + HTTPS zip pull).

### OPlus & Xiaomi advert layout
- ADV_IND connectable: `flags + uuid128(00003331-...-008123456789) + svcdata[0x012a]=<6 ascii id>`
- SCAN_RSP: `svcdata[0x0204]=<10B prefix e.g. "EEFB52FY1x"><name pad 14B><0x01>`
- Companion advert (separate RPA): `uuid16 0x3334 + svcdata[0x3334]=<name ascii>`
- Cross-vendor generic: `0x01ff` + `0xffff` svcdata keys.
- Xiaomi: `0x1b1e` + `0xffff`.

### Windows
- One developer's Bluetooth adapter lacks a working LE transmit path, so
  this path was never tested — you will need to test it yourself.

### Env
- Python 3.13 x64; WinRT 3.2.1 components installed (winrt-* packages).
- `python -m tandem.mta --name TANDEM-PC --profile oppo` — rotation code in
  `mta.py` handles single-slot adapters; PC-side broadcast unverified.

## Android MTA receiver — WORKING end-to-end

`android/app/src/main/java/com/tandem/app/Mta.kt` — MtaService (foreground
service) implements the full receiver: BLE advert (uuid128 0x3331 + svcdata
0x01ff + 27B name blob 0xffff in scan-rsp) → GATT 0x9955 (9954 R|W status JSON
{state,mac,key,frequency}, 9953 W creds) → ECDH P-256 + AES-256-CTR →
WifiP2pConfig.Builder join → WSS handshake → HTTPS zip pull →
`getExternalFilesDir(DOWNLOADS)/Tandem/`.

Test devices: OnePlus Ace 2 Pro (sender, native 互传) → Xiaomi 12S
(receiver). jpg + mp4 received.
OnePlus creds JSON uses different fields/order than Xiaomi's — parser handles both.

Device-list contract: scanner requires BOTH svcdata segments in one
ScanRecord: 6-byte svcdata (uuid encodes brand+5GHz flag:
0x012a=OPlus, 0x1b1e=Xiaomi, 0x01ff=generic/CatShare) AND 27-byte svcdata
([8..9]=senderId rand16, [10..25]=name16, [26]=0x01).

### Android build (WSL — Windows SDK deleted)
- SDK: `~/android-sdk` (platforms android-36, build-tools 35.0.0), JDK:
  `openjdk-21-jdk-headless` (javac required — JRE alone fails toolchain).
- Build: `cd <project>/android && ANDROID_HOME=~/android-sdk ./gradlew :app:assembleDebug`
- local.properties has NO sdk.dir (relies on ANDROID_HOME env).
- Logs: `adb logcat -s MtaSvc:V` (tag is MtaSvc not MTA).
- Received files: `/sdcard/Android/data/com.tandem.app/files/Download/Tandem/`
  (hidden from builtin file manager on Android 11+ — implement
  MANAGE_EXTERNAL_STORAGE "all files access" yourself).

### Pitfalls hit
- `Settings.Secure.getString("bluetooth_name")` → SecurityException on
  targetSdk>31. Use `BluetoothAdapter.getName()`.
- WifiP2pManager.initialize needs manifest `CHANGE_WIFI_STATE` +
  `CHANGE_NETWORK_STATE`; `NEARBY_WIFI_DEVICES` needs
  `usesPermissionFlags="neverForLocation"` for API33+ P2P.

## Android Tandem client/hub — phone-to-phone

App is now a full Tandem client + phone-as-hub. Files:
`MainActivity.kt` (UI), `Hub.kt` (http/https client + minimal ws
impl + NSD discovery + multipart upload), `PhoneHub.kt` (NanoHTTPD/NanoWSD
server on :9787, http only), `SyncService.kt` (persistent ws client FGS),
`ShareActivity.kt` (ACTION_SEND/SEND_MULTIPLE), `Mta.kt` (互传 receiver).

### Hub API surface (PhoneHub mirrors tandem/server.py)
`/api/info /ca.crt /api/pair /api/items /api/files(+GET id) /api/text
/api/clipboard(GET|POST) /ws` + mDNS `_tandem._tcp`. QR payload =
`http://<lan-ip>:9787/?token=<master>`; client auto-picks http/https from
scheme (`usesCleartextTraffic=true` required for http hubs!).
Auth = `?token=` query param everywhere (no Authorization header support).

### NanoHTTPD pitfalls (PhoneHub)
- `parseBody` decodes `application/json` as **latin-1** → Chinese clipboard
  arrived garbled. Fix: read `s.inputStream` raw as UTF-8 (content-length
  bounded). urlencoded params are fine (`decodePercent`→UTF8).
- UI callbacks (`onItemsChanged`, status) must be posted to main thread —
  NanoHTTPD worker threads touching views → CalledFromWrongThread → 500.
- `NanoWSD` upgrade: implement `openWebSocket()` + `onMessage(frame)` (NOT
  onText/onBinary — those don't exist in 2.3.1). Route `/ws` through
  `super.serve(s)` when `isWebsocketRequested`.
- HTTP `SOCKET_READ_TIMEOUT` governs ws lifetime → server pings every 3s;
  client auto-pongs. Some vendors (e.g. OPlus) freeze sockets → still see
  ~30-60s flaps — SyncService resyncs `/api/clipboard` after every reconnect
  to cover gaps.

### Clipboard sync — test results (2026-10-08/09)
- Android 10+: bg **read** clipboard = blocked everywhere; bg **write** =
  vendor-dependent.
- **Xiaomi MIUI / HyperOS**: `adb shell appops set --uid com.tandem.app
  READ_CLIPBOARD allow` works (shell allowed) → service clipboard listener
  reads in background → copy anywhere auto-pushes. WRITE also fine.
- **OPlus ColorOS 15/16**: shell appops BLOCKED
  (`MANAGE_APP_OPS_MODES` denied). logcat shows
  `ClipboardService: Denying clipboard access ... not in focus nor system
  service` for BOTH read (op29) and write (op30) while backgrounded.
  Focusable 1×1 overlay trick does NOT defeat it. → foreground-only send;
  receive = heads-up notification → tap → resume path force-writes clip.
- 1×1 focusable overlay WITHOUT FLAG_NOT_FOCUSABLE = steals IME focus
  ("无法呼叫输入法"); now flips focusable only during a ~400ms read window,
  but we are still researching ways around the background clipboard
  read/write restriction.

### Keep-alive stack (both services)
`START_STICKY` + foreground service + `PARTIAL_WAKE_LOCK` +
`WIFI_MODE_FULL_LOW_LATENCY` WifiLock + `onTaskRemoved`→AlarmManager 1s
restart + battery-opt whitelist prompt (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
+ `dumpsys deviceidle whitelist +com.tandem.app` works via adb (equivalent).
Swipe-kill on ColorOS still kills everything incl. FGS — restart intent
covers it as long as task isn't force-stopped.

### Verified flows
- QR pair OnePlus↔Xiaomi hub, file upload→inbox→download→FileProvider open,
  clipboard ws realtime sync (fg), bg receive w/ notification fallback,
  MTA native 互传 receive (jpg/mp4) — all working.

中文版见 `AGENTS.zh-CN.md`.

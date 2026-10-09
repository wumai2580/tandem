package com.tandem.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var pairCard: TextView
    private lateinit var hubsList: TextView
    private lateinit var inboxList: LinearLayout
    private lateinit var clipStatus: TextView
    private lateinit var hubBtn: Button
    private lateinit var hubInfo: TextView
    private lateinit var hubQr: android.widget.ImageView
    private lateinit var receivedList: LinearLayout

    // ------------------------------------------------------------- scanners

    private val scanner = registerForActivityResult(ScanContract()) { res ->
        val content = res.contents ?: return@registerForActivityResult
        if (content.startsWith("WIFI:")) joinWifi(content) else pair(content)
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        status.text = "发送 ${uris.size} 个文件…"
        thread {
            try {
                uris.forEach { Hub.postFile(this, it) }
                runOnUiThread { status.text = "已发送 ${uris.size} 个文件"; refreshItems() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "发送失败：${e.message}" }
            }
        }
    }

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (!granted.values.all { it }) return@registerForActivityResult toast("需要权限")
        when (pendingAction) {
            "bench" -> runBench()
            "mta" -> startMta(mtaButtonRef)
            else -> blePair()
        }
    }

    private var pendingAction = "pair"
    private var mtaButtonRef: Button? = null

    // ------------------------------------------------------------- UI build

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFF141B33.toInt()); cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), dp(10), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(10))
        layoutParams = lp
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(0xFF8B93B8.toInt())
        setPadding(0, 0, 0, dp(6))
    }

    private fun flatButton(text: String, onClick: (Button) -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setOnClickListener { onClick(this) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = dp(18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Tandem ₍ᐢ·ᴗ·ᐢ₎"; textSize = 26f
            setPadding(0, dp(6), 0, dp(2))
        })
        pairCard = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFB8C0E0.toInt()); setPadding(0, 0, 0, dp(14))
        }
        root.addView(pairCard)

        // ---- pair section ----
        card().also { c ->
            c.addView(header("配对 / PAIR"))
            c.addView(row(
                flatButton("扫码配对") {
                    scanner.launch(ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("扫描电脑上的 Tandem 二维码"); setBeepEnabled(false)
                    })
                },
                flatButton("蓝牙配对") { blePair() }))
            c.addView(flatButton("搜索局域网中枢") { findHubs() })
            hubsList = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) }
            c.addView(hubsList)
            root.addView(c)
        }

        // ---- send section ----
        card().also { c ->
            c.addView(header("发送到电脑 / SEND"))
            val input = EditText(this).apply {
                hint = "输入文字或链接…"; textSize = 14f; setSingleLine()
            }
            c.addView(input)
            c.addView(row(
                flatButton("选择文件") {
                    filePicker.launch(arrayOf("*/*"))
                },
                flatButton("发送文字") {
                    val t = input.text.toString()
                    if (t.isBlank()) return@flatButton toast("先输入内容")
                    input.setText("")
                    status.text = "发送中…"
                    thread {
                        try {
                            Hub.postText(this@MainActivity, t)
                            runOnUiThread { status.text = "已发送" }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "发送失败：${e.message}" }
                        }
                    }
                }))
            root.addView(c)
        }

        // ---- clipboard section ----
        card().also { c ->
            c.addView(header("剪贴板 / CLIPBOARD"))
            c.addView(row(
                flatButton("本机 → 电脑") {
                    val t = clipRead()
                    if (t.isNullOrEmpty()) return@flatButton toast("剪贴板为空")
                    thread {
                        try {
                            Hub.postClipboard(this@MainActivity, t)
                            runOnUiThread { status.text = "剪贴板已推到电脑" }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "推送失败：${e.message}" }
                        }
                    }
                },
                flatButton("电脑 → 本机") {
                    thread {
                        try {
                            val t = Hub.getClipboard(this@MainActivity)
                            runOnUiThread {
                                if (t.isEmpty()) toast("电脑剪贴板为空")
                                else { clipWrite(t); status.text = "已复制到本机剪贴板" }
                            }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "拉取失败：${e.message}" }
                        }
                    }
                }))
            clipStatus = TextView(this).apply {
                textSize = 12f; setTextColor(0xFF8B93B8.toInt()); setPadding(0, dp(4), 0, 0)
            }
            c.addView(clipStatus)
            root.addView(c)
        }

        // ---- MTA ----
        card().also { c ->
            c.addView(header("厂商互传 / MTA"))
            val mtaBtn = flatButton("互传接收 (关)") {}
            mtaBtn.setOnClickListener {
                if (MtaService.running) {
                    stopService(Intent(this, MtaService::class.java))
                    mtaBtn.text = "互传接收 (关)"
                } else {
                    pendingAction = "mta"
                    if (ensureMtaPerms()) startMta(mtaBtn)
                }
            }
            mtaButtonRef = mtaBtn
            c.addView(mtaBtn)
            root.addView(c)
        }

        // ---- phone-as-hub ----
        card().also { c ->
            c.addView(header("本机中枢 / THIS PHONE AS HUB"))
            hubInfo = TextView(this).apply {
                textSize = 13f; setTextColor(0xFF8B93B8.toInt()); setPadding(0, dp(4), 0, dp(4))
            }
            hubQr = android.widget.ImageView(this).apply {
                visibility = View.GONE
                val lp = LinearLayout.LayoutParams(dp(190), dp(190))
                lp.gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = lp
            }
            hubBtn = flatButton("本机中枢 (关)") {}
            hubBtn.setOnClickListener {
                if (PhoneHubService.running) {
                    stopService(Intent(this, PhoneHubService::class.java))
                    hubBtn.text = "本机中枢 (关)"
                    hubQr.visibility = View.GONE
                    hubInfo.text = ""
                } else {
                    pendingAction = "mta"   // same permission set (notif+bt)
                    if (ensureMtaPerms()) startPhoneHub()
                }
            }
            c.addView(hubBtn)
            c.addView(hubInfo)
            c.addView(hubQr)
            c.addView(header("本机收到的 / RECEIVED"))
            receivedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(receivedList)
            c.addView(flatButton("刷新收件") { refreshReceived() })
            root.addView(c)
        }

        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF8B93B8.toInt())
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        root.addView(status)

        // ---- inbox ----
        card().also { c ->
            c.addView(header("电脑收件箱 / INBOX"))
            inboxList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(inboxList)
            c.addView(flatButton("刷新") { refreshItems() })
            root.addView(c)
        }

        root.addView(flatButton("解除配对 / Forget") {
            Hub.forget(this@MainActivity)
            stopService(Intent(this, SyncService::class.java))
            refresh(); toast("已解除")
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
        handleExtras(intent)
        MtaService.statusCb = { s -> runOnUiThread { status.text = "MTA: $s" } }
        PhoneHubService.statusCb = { s -> runOnUiThread { status.text = "Hub: $s" } }
        PhoneHubService.onItemsChanged = { refreshReceived() }
    }

    // ------------------------------------------------------------- lifecycle

    @Volatile private var resumed = false

    override fun onResume() {
        super.onResume()
        resumed = true
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .addPrimaryClipChangedListener(clipListener)
        // catch up on copies made while another app was foreground
        val cur = clipRead()
        // remote clip that couldn't land while backgrounded — force-write now
        if (SyncService.lastClip.isNotEmpty() && SyncService.lastClip != cur) {
            clipWrite(SyncService.lastClip)
            Log.i("TandemSync", "resume补写剪贴板 len=${SyncService.lastClip.length}")
        }
        cur?.takeIf { it != lastClip }?.let {
            markClip(it)
            pushClip(it)                          // to paired hub, if any
            PhoneHubService.notifyLocalClip(it)   // we may also BE the hub
        }
        refreshItems()
        startSync()
        refreshHubUi()
        refreshReceived()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .removePrimaryClipChangedListener(clipListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
    }

    // ------------------------------------------------------------- sync (ws)

    private fun startSync() {
        SyncService.statusCb = { clipStatus.text = it }
        SyncService.remoteClipCb = { status.text = "剪贴板已同步（来自对端）" }
        SyncService.inboxCb = { refreshItems() }
        if (!Hub.paired(this)) return
        androidx.core.content.ContextCompat.startForegroundService(
            this, Intent(this, SyncService::class.java))
        askBatteryWhitelist()
        askOverlay()
    }

    /** 国产 ROM 后台冻结会掐断长连接 — 引导加入电池优化白名单 */
    private fun askBatteryWhitelist() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        android.app.AlertDialog.Builder(this)
            .setTitle("保持后台同步")
            .setMessage("为让剪贴板/文件在后台实时同步，请允许 Tandem 不受电池优化限制（弹窗选\"允许\"）。另建议：最近任务里给本应用加锁，并在系统自启动管理中允许自启动。")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (_: Exception) {}
                }
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    /** 1×1 可获焦悬浮窗让 uid 持有焦点窗口 → 后台可读剪贴板（很多 ROM 适用） */
    private fun askOverlay() {
        if (Settings.canDrawOverlays(this)) return
        android.app.AlertDialog.Builder(this)
            .setTitle("后台读剪贴板")
            .setMessage("若想让\"在任何 app 里复制都自动同步\"，请授予悬浮窗权限（系统限制的后台读板绕过手段）。")
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    // ------------------------------------------------------------- clipboard

    private fun clipRead(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip ?: return null
        return if (c.itemCount > 0) c.getItemAt(0).coerceToText(this)?.toString() else null
    }

    private fun clipWrite(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("tandem", s))
    }

    /** last text we pushed or received — suppresses listener echo loops. */
    private val lastClip get() = SyncService.lastClip
    private fun markClip(t: String) { SyncService.lastClip = t }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val t = clipRead()
        if (!t.isNullOrEmpty() && t != lastClip) {
            markClip(t)
            pushClip(t)
        }
    }

    private fun pushClip(t: String) {
        thread {
            if (!SyncService.sendClipboard(this, t))
                try { Hub.postClipboard(this, t) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------- inbox

    private fun refreshItems() {
        if (!Hub.paired(this)) { inboxList.removeAllViews(); return }
        thread {
            try {
                val items = Hub.listItems(this).take(30)
                runOnUiThread { renderItems(items) }
            } catch (e: Exception) {
                runOnUiThread {
                    inboxList.removeAllViews()
                    inboxList.addView(TextView(this).apply {
                        text = "无法获取：${e.message}"; textSize = 13f
                        setTextColor(0xFF8B93B8.toInt())
                    })
                }
            }
        }
    }

    private fun renderItems(items: List<org.json.JSONObject>) {
        inboxList.removeAllViews()
        if (items.isEmpty()) {
            inboxList.addView(TextView(this).apply {
                text = "收件箱是空的"; textSize = 13f; setTextColor(0xFF8B93B8.toInt())
            })
            return
        }
        val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (item in items) {
            val kind = item.optString("kind")
            val time = df.format(java.util.Date((item.optDouble("ts") * 1000).toLong()))
            val label = when (kind) {
                "text" -> "📝 " + item.optString("text").replace("\n", " ").take(60)
                else -> "📄 ${item.optString("name", "file")} " +
                        "(${fmtSize(item.optLong("size"))})"
            }
            inboxList.addView(TextView(this).apply {
                text = "$label\n$time · ${item.optString("from", "?")}"
                textSize = 13.5f; setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener {
                    if (kind == "text") {
                        clipWrite(item.optString("text")); toast("已复制")
                    } else downloadItem(item)
                }
            })
        }
    }

    // ------------------------------------------------------------- phone hub

    private fun startPhoneHub() {
        hubBtn.text = "本机中枢 (开)"
        val i = Intent(this, PhoneHubService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i)
        else startService(i)
        // Give it a beat to bind, then show QR + info
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            refreshHubUi()
        }, 700)
    }

    private fun refreshHubUi() {
        if (!PhoneHubService.running) return
        hubBtn.text = "本机中枢 (开)"
        val store = PhoneHubService.HubStore(this)
        val url = "http://${lanIp()}:${PhoneHubService.PORT}/?token=${store.masterToken}"
        hubInfo.text = "地址：$url\n另一台设备扫下方二维码即可配对"
        try {
            val bmp = com.journeyapps.barcodescanner.BarcodeEncoder()
                .encodeBitmap(url, com.google.zxing.BarcodeFormat.QR_CODE, 700, 700)
            hubQr.setImageBitmap(bmp)
            hubQr.visibility = View.VISIBLE
        } catch (e: Exception) {
            hubInfo.text = "地址：$url\n（二维码生成失败，手动输入上面的链接）"
        }
        refreshReceived()
    }

    private fun refreshReceived() {
        receivedList.removeAllViews()
        if (!PhoneHubService.running) return
        try {
            val store = PhoneHubService.HubStore(this)
            val a = store.items()
            val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            var shown = 0
            for (i in 0 until minOf(a.length(), 20)) {
                val item = a.getJSONObject(i)
                val kind = item.optString("kind")
                val time = df.format(java.util.Date((item.optDouble("ts") * 1000).toLong()))
                val label = when (kind) {
                    "text" -> "📝 " + item.optString("text").replace("\n", " ").take(60)
                    else -> "📄 ${item.optString("name", "file")} (${fmtSize(item.optLong("size"))})"
                }
                shown++
                receivedList.addView(TextView(this).apply {
                    text = "$label\n$time · ${item.optString("from", "?")}"
                    textSize = 13.5f; setPadding(dp(4), dp(8), dp(4), dp(8))
                    setOnClickListener {
                        if (kind == "text") {
                            clipWrite(item.optString("text")); toast("已复制")
                        } else {
                            val f = store.fileFor(item.optString("id"))
                            if (f != null) {
                                val dest = java.io.File(
                                    getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                                    "Tandem").apply { mkdirs() }
                                val out = java.io.File(dest, item.optString("name", "file"))
                                f.copyTo(out, true)
                                toast("已保存 ${out.absolutePath}")
                                openFile(out)
                            }
                        }
                    }
                })
            }
            if (shown == 0) receivedList.addView(TextView(this).apply {
                text = "暂无"; textSize = 13f; setTextColor(0xFF8B93B8.toInt())
            })
        } catch (_: Exception) {}
    }

    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "${n}B"
        n < 1 shl 20 -> "${n / 1024}KB"
        else -> "%.1fMB".format(n / 1048576.0)
    }

    private fun downloadItem(it: org.json.JSONObject) {
        val id = it.optString("id"); val name = it.optString("name", "file")
        status.text = "下载 $name…"
        thread {
            try {
                val bytes = Hub.fetchItem(this, id)
                val dir = java.io.File(
                    getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "Tandem").apply { mkdirs() }
                val f = java.io.File(dir, name)
                f.writeBytes(bytes)
                runOnUiThread { status.text = "已保存 ${f.absolutePath}"; openFile(f) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "下载失败：${e.message}" }
            }
        }
    }

    private fun openFile(f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "com.tandem.app.files", f)
            val ext = f.extension.lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) { /* no viewer — file is saved anyway */ }
    }

    // ------------------------------------------------------------- pairing

    private fun refresh() {
        val ep = Hub.endpoint(this)
        pairCard.text = if (ep != null)
            "✓ 已配对：${ep.name.ifBlank { ep.host }}:${ep.port}"
        else "未配对 — 扫码或蓝牙配对电脑上的 Tandem"
    }

    private fun pair(url: String) {
        val m = Regex("(https?)://([^:/]+):(\\d+)/?\\?token=([^&\\s]+)").find(url)
            ?: return toast("不是有效的配对链接")
        val (scheme, host, port, token) = m.destructured
        status.text = "配对中…"
        thread {
            try {
                Hub.pair(this, host, port.toInt(), token, android.os.Build.MODEL, scheme)
                runOnUiThread { refresh(); toast("配对成功"); refreshItems(); startSync() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "配对出错：${e.message}" }
            }
        }
    }

    private fun findHubs() {
        hubsList.text = "搜索中…"
        thread {
            try {
                val list = Hub.discoverBlocking(this, 4000)
                runOnUiThread {
                    hubsList.text = if (list.isEmpty()) "未发现中枢"
                    else list.joinToString("\n") { "• ${it.name} — ${it.host}:${it.port}" }
                }
            } catch (e: Exception) {
                runOnUiThread { hubsList.text = "搜索失败：${e.message}" }
            }
        }
    }

    /** Scan a WIFI: QR → offer system "join network" sheet (Android 10+). */
    private fun joinWifi(qr: String) {
        fun field(k: String) = Regex("$k:((?:\\\\.|[^;\\\\:])*)").find(qr)
            ?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1")
        val ssid = field("S") ?: return toast("无法解析 Wi-Fi 码")
        val pass = field("P") ?: ""
        val suggestion = android.net.wifi.WifiNetworkSuggestion.Builder()
            .setSsid(ssid).setWpa2Passphrase(pass).build()
        val i = Intent("android.settings.WIFI_ADD_NETWORKS").apply {
            putParcelableArrayListExtra(
                "android.provider.extra.EXTRA_WIFI_NETWORK_LIST",
                arrayListOf<android.os.Parcelable>(suggestion))
        }
        try { startActivity(i) }
        catch (e: Exception) { toast("请在系统相机/设置中扫码加入 Wi-Fi：$ssid / $pass") }
    }

    // ------------------------------------------------------------- BLE pair

    private fun blePair() {
        pendingAction = "pair"
        if (!ensureBlePerms()) return
        status.text = "蓝牙配对中…"
        thread {
            try {
                kotlinx.coroutines.runBlocking {
                    Ble.pairViaBle(this@MainActivity, android.os.Build.MODEL) { s ->
                        runOnUiThread { status.text = s }
                    }
                }
                runOnUiThread { refresh(); toast("配对成功"); refreshItems(); startSync() }
            } catch (e: Exception) {
                Ble.dbg(this@MainActivity, "FAIL ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { status.text = "蓝牙配对失败：${e.message}" }
            }
        }
    }

    // ------------------------------------------------------------- MTA / misc

    private fun ensureMtaPerms(): Boolean {
        val sdk = android.os.Build.VERSION.SDK_INT
        val need = mutableListOf<String>()
        if (sdk >= 31) {
            need += android.Manifest.permission.BLUETOOTH_ADVERTISE
            need += android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            need += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (sdk >= 33) {
            need += android.Manifest.permission.NEARBY_WIFI_DEVICES
            need += android.Manifest.permission.POST_NOTIFICATIONS
        } else if (sdk <= 32) {
            need += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = need.distinct().filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { perms.launch(missing.toTypedArray()); return false }
        return true
    }

    private fun startMta(btn: Button?) {
        btn?.text = "互传接收 (开)"
        mtaButtonRef = btn
        val i = Intent(this, MtaService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i)
        else startService(i)
    }

    private fun ensureBlePerms(): Boolean {
        val need = if (android.os.Build.VERSION.SDK_INT >= 31) listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = need.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { perms.launch(missing.toTypedArray()); return false }
        return true
    }

    /** adb hooks: --ez bench true [--ez fast true], --ez mtaScan true */
    private fun handleExtras(i: Intent) {
        if (i.getBooleanExtra("bench", false)) {
            pendingAction = "bench"
            if (ensureBlePerms()) runBench(i.getBooleanExtra("fast", false))
        }
        if (i.getBooleanExtra("mtaScan", false) && ensureBlePerms()) runMtaScan()
        if (i.getBooleanExtra("pcScan", false) && ensureBlePerms()) runPcScan()
    }

    private fun runBench(fast: Boolean = false) {
        status.text = "BLE 测速中…"
        thread {
            try {
                kotlinx.coroutines.runBlocking {
                    Ble.bench(this@MainActivity, 200, fast) { s -> runOnUiThread { status.text = s } }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "测速失败：${e.message}" }
            }
        }
    }

    private fun runMtaScan() {
        status.text = "扫描 0x3331 信标…"
        thread {
            val bt = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
            val sc = bt.bluetoothLeScanner
            val mta = android.os.ParcelUuid(
                java.util.UUID.fromString("00003331-0000-1000-8000-008123456789"))
            val found = java.util.concurrent.CopyOnWriteArrayList<String>()
            val cb = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(t: Int, r: android.bluetooth.le.ScanResult) {
                    val s = "${r.device.address} rssi=${r.rssi}"
                    if (found.addIfAbsent(s))
                        runOnUiThread { status.text = "发现:\n" + found.joinToString("\n") }
                }
            }
            sc.startScan(
                listOf(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(mta).build()),
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb)
            Thread.sleep(10000)
            sc.stopScan(cb)
            runOnUiThread { if (found.isEmpty()) status.text = "未发现 0x3331 信标" }
        }
    }

    private fun runPcScan() {
        status.text = "扫描 mfr-911…"
        thread {
            val bt = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
            val sc = bt.bluetoothLeScanner
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            val cb = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(t: Int, r: android.bluetooth.le.ScanResult) {
                    val m = r.scanRecord?.getManufacturerSpecificData(911) ?: return
                    val s = "${r.device.address} rssi=${r.rssi} ${m.joinToString("") { "%02x".format(it) }}"
                    if (seen.addIfAbsent(s)) {
                        android.util.Log.i("TandemPcScan", s)
                        runOnUiThread { status.text = "mfr911:\n" + seen.joinToString("\n") }
                    }
                }
            }
            sc.startScan(null,
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb)
            Thread.sleep(15000)
            sc.stopScan(cb)
            runOnUiThread { if (seen.isEmpty()) status.text = "未捕获 mfr-911 包" }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

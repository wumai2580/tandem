package com.tandem.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var devices: TextView

    private val scanner = registerForActivityResult(ScanContract()) { res ->
        val content = res.contents ?: return@registerForActivityResult
        if (content.startsWith("WIFI:")) joinWifi(content) else pair(content)
    }

    /** Scan a WIFI: QR → offer system "join network" sheet (Android 10+). */
    private fun joinWifi(qr: String) {
        fun field(k: String) = Regex("$k:((?:\\\\.|[^;\\\\:])*)").find(qr)
            ?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1")
        val ssid = field("S") ?: return toast("无法解析 Wi-Fi 码")
        val pass = field("P") ?: ""
        val suggestion = android.net.wifi.WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()
        val i = Intent("android.settings.WIFI_ADD_NETWORKS").apply {
            putParcelableArrayListExtra(
                "android.provider.extra.EXTRA_WIFI_NETWORK_LIST",
                arrayListOf<android.os.Parcelable>(suggestion)
            )
        }
        try { startActivity(i) } catch (e: Exception) {
            toast("请在系统相机/设置中扫码加入 Wi-Fi：$ssid / $pass")
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Tandem"; textSize = 24f; setPadding(0, 0, 0, pad)
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, pad) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "扫码配对 / Scan QR to pair"
            setOnClickListener {
                scanner.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("扫描电脑上的 Tandem 二维码")
                    setBeepEnabled(false)
                })
            }
        })
        root.addView(Button(this).apply {
            text = "蓝牙配对 / Pair via Bluetooth"
            setOnClickListener { blePair() }
        })
        root.addView(Button(this).apply {
            text = "搜索局域网设备 / Find hubs"
            setOnClickListener { findHubs() }
        })
        root.addView(Button(this).apply {
            text = "解除配对 / Forget"
            setOnClickListener { Hub.forget(this@MainActivity); refresh(); toast("已解除") }
        })
        devices = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, 0) }
        root.addView(devices)
        setContentView(root)
        refresh()
        handleExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
    }

    /** adb hooks: --ez bench true [--ez fast true], --ez mtaScan true */
    private fun handleExtras(i: Intent) {
        if (i.getBooleanExtra("bench", false)) {
            pendingAction = "bench"
            if (ensureBlePerms()) runBench(i.getBooleanExtra("fast", false))
        }
        if (i.getBooleanExtra("mtaScan", false) && ensureBlePerms()) runMtaScan()
    }

    private fun refresh() {
        val ep = Hub.endpoint(this)
        status.text = if (ep != null)
            "已配对 / Paired: ${ep.name.ifBlank { ep.host }}:${ep.port}\n凭证已保存，无需重复扫码"
        else
            "未配对 / Not paired — scan the QR shown by `tandem` on your computer"
    }

    private fun pair(url: String) {
        val m = Regex("https?://([^:/]+):(\\d+)/?\\?token=([^&\\s]+)").find(url)
            ?: return toast("不是有效的配对链接")
        val (host, port, token) = m.destructured
        status.text = "配对中…"
        thread {
            try {
                Hub.pair(this, host, port.toInt(), token, android.os.Build.MODEL)
                runOnUiThread { refresh(); toast("配对成功") }
            } catch (e: Exception) {
                runOnUiThread { status.text = "配对出错：${e.message}" }
            }
        }
    }

    private fun findHubs() {
        devices.text = "搜索中…"
        thread {
            try {
                val list = Hub.discoverBlocking(this, 4000)
                runOnUiThread {
                    devices.text = if (list.isEmpty()) "未发现设备"
                    else "发现：\n" + list.joinToString("\n") { "• ${it.name} — ${it.host}:${it.port}" }
                }
            } catch (e: Exception) {
                runOnUiThread { devices.text = "搜索失败：${e.message}" }
            }
        }
    }

    private var pendingAction = "pair"

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (!granted.values.all { it }) return@registerForActivityResult toast("需要蓝牙权限")
        if (pendingAction == "bench") runBench() else blePair()
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

    /** Debug: scan for MTA (互传联盟) 0x3331 beacons to verify mta.py is on air. */
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
                runOnUiThread { refresh(); toast("配对成功") }
            } catch (e: Exception) {
                Ble.dbg(this@MainActivity, "FAIL ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { status.text = "蓝牙配对失败：${e.message}" }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

package com.tandem.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.MacAddress
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * MTA (互传联盟) receiver — makes this phone appear in Xiaomi/OPPO/vivo
 * native share sheets as a receive target.
 *
 * Wire format (verified against real Xiaomi + OnePlus captures):
 *   ADV_IND:  flags + uuid128(0x3331) + svcdata[0x01ff] = rand16 + 4x00
 *   SCAN_RSP: svcdata[0xffff] = 8x00 + rand16 + name[16] + 0x01
 *   (sender's share list keys on svcdata sizes: 6B = session, 27B = name)
 *   GATT 0x9955:  0x9954 read/write -> {"state","mac","key","frequency"}
 *                 0x9953 write      <- {"ssid","psk","mac","port","key"?}
 *   then join the sender's Wi-Fi Direct group, WSS handshake, pull the ZIP.
 */
class MtaService : Service() {

    companion object {
        const val TAG = "MtaSvc"
        const val CH_ID = "mta_rx"
        var running = false; private set
        var statusCb: ((String) -> Unit)? = null
        fun status(s: String) {
            Log.i(TAG, s)
            android.os.Handler(android.os.Looper.getMainLooper()).post { statusCb?.invoke(s) }
        }

        val ADV_UUID: UUID = UUID.fromString("00003331-0000-1000-8000-008123456789")
        val SD_SESSION: UUID = UUID.fromString("000001ff-0000-1000-8000-00805f9b34fb")
        val SD_NAME: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        val SVC_UUID: UUID = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")
        val CHAR_STATUS: UUID = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")
        val CHAR_P2P: UUID = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var gatt: BluetoothGattServer
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advCb: AdvertiseCallback? = null
    private var kp: KeyPair? = null
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var rxDir: File? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        rxDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "Tandem") } ?: File(filesDir, "Tandem")
        rxDir!!.mkdirs()
        running = true
        startFg()
        thread(name = "mta") { run() }
    }

    override fun onDestroy() {
        running = false
        stopAdvert()
        try { gatt.close() } catch (_: Exception) {}
        try { p2pChannel?.let { p2pManager?.removeGroup(it, null) } } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- BLE --

    private fun run() {
        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) { status("蓝牙未开启"); return }
        kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        startGatt()
        startAdvert(bt)
    }

    private fun name16(): ByteArray {
        val btName = try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.name
        } catch (_: SecurityException) { null }
        val n = (btName ?: Build.MODEL ?: "Tandem").toByteArray(Charsets.UTF_8)
        return n.copyOf(16)
    }

    private fun startAdvert(bt: android.bluetooth.BluetoothAdapter) {
        advertiser = bt.bluetoothLeAdvertiser ?: run { status("无 BLE 外设能力"); return }
        val rand16 = ByteArray(2).also { SecureRandom().nextBytes(it) }
        // 8x00 + rand16(senderId) + name[16] + 0x01 — matches Xiaomi capture
        val nameBlob = ByteArray(27)
        rand16.copyInto(nameBlob, 8)
        name16().copyInto(nameBlob, 10)
        nameBlob[26] = 0x01
        val session = rand16 + ByteArray(4)  // 6B session payload

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val full = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(ADV_UUID))
            .addServiceData(ParcelUuid(SD_SESSION), session)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        // Fallback if uuid+svcdata overflows a tiny advert buffer — share-list
        // scanners key on the serviceData entries, not the service uuid.
        val dataOnly = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SD_SESSION), session)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        val scanRsp = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SD_NAME), nameBlob)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        advCb = object : AdvertiseCallback() {
            var fellBack = false
            override fun onStartSuccess(s: AdvertiseSettings?) {
                status("互传接收中 — 在分享面板应能看到本机" + if (fellBack) " (svcdata-only)" else "")
            }

            override fun onStartFailure(code: Int) {
                if (!fellBack && code == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    fellBack = true
                    advertiser?.startAdvertising(settings, dataOnly, null, this)
                } else status("广播失败 code=$code")
            }
        }
        advertiser!!.startAdvertising(settings, full, scanRsp, advCb)
    }

    private fun stopAdvert() {
        try { advCb?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) {}
    }

    // --------------------------------------------------------------- GATT --

    private fun localMac(): String {
        // real devices send their (randomized) P2P MAC; Android blocks reading
        // the real one — stable per-install local-admin value instead
        val p = getSharedPreferences("mta", MODE_PRIVATE)
        var v = p.getLong("mac", 0L)
        if (v == 0L) {
            v = SecureRandom().nextLong() and 0xFFFFFFFFFFL
            v = (v or 0x020000000000L) and 0xFEFFFFFFFFFFL
            p.edit().putLong("mac", v).apply()
        }
        return (0..5).joinToString(":") { "%02x".format((v shr (8 * (5 - it))) and 0xFF) }
    }

    private fun statusJson(): ByteArray = JSONObject()
        .put("state", 0)
        .put("mac", localMac())
        .put("key", android.util.Base64.encodeToString(kp!!.public.encoded, android.util.Base64.NO_WRAP))
        .put("frequency", 5300)   // Xiaomi sends this; marks 5GHz-capable
        .toString().toByteArray()

    private fun startGatt() {
        val mgr = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        gatt = mgr.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, s: BluetoothGattService) {
                Log.i(TAG, "gatt service added rc=$status")
            }

            override fun onConnectionStateChange(d: BluetoothDevice, s: Int, ns: Int) {
                status("GATT ${d.address} ${if (ns == BluetoothGatt.STATE_CONNECTED) "连上" else "断开"}")
            }

            override fun onCharacteristicReadRequest(
                d: BluetoothDevice, rid: Int, off: Int, c: BluetoothGattCharacteristic
            ) {
                if (c.uuid == CHAR_STATUS) {
                    val v = statusJson()
                    status("对端读取 status")
                    gatt.sendResponse(
                        d, rid, BluetoothGatt.GATT_SUCCESS, off,
                        v.copyOfRange(off.coerceAtMost(v.size), v.size)
                    )
                } else {
                    gatt.sendResponse(d, rid, BluetoothGatt.GATT_FAILURE, off, null)
                }
            }

            override fun onCharacteristicWriteRequest(
                d: BluetoothDevice, rid: Int, c: BluetoothGattCharacteristic,
                prepared: Boolean, rsp: Boolean, off: Int, value: ByteArray
            ) {
                if (rsp) gatt.sendResponse(d, rid, BluetoothGatt.GATT_SUCCESS, off, null)
                when (c.uuid) {
                    CHAR_P2P -> onP2p(value)
                    CHAR_STATUS -> status("对端写 status: ${String(value).take(120)}")
                }
            }
        }) ?: run { status("GATT server 打开失败"); return }

        val svc = BluetoothGattService(SVC_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                CHAR_STATUS,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                CHAR_P2P,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        gatt.addService(svc)
    }

    // ------------------------------------------------------------- crypto --

    private fun onP2p(raw: ByteArray) {
        try {
            val s = String(raw, Charsets.UTF_8)
            val data = JSONObject(s.substring(s.indexOf('{').coerceAtLeast(0)))
            status("收到 p2p 凭证 keys=${data.keys().asSequence().toList()}")
            val key = data.optString("key", "")
            if (key.isNotEmpty()) {
                val dec = cipherFor(key)
                for (k in listOf("ssid", "psk", "mac")) {
                    if (data.has(k)) data.put(k, dec(data.getString(k)))
                }
            }
            thread {
                transfer(
                    data.getString("ssid"), data.getString("psk"),
                    data.getString("mac"), data.getInt("port")
                )
            }
        } catch (e: Exception) {
            status("p2p 解析失败: ${e.message}")
        }
    }

    private fun cipherFor(peerB64: String): (String) -> String {
        val peer = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(android.util.Base64.decode(peerB64, 0)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp!!.private)
        ka.doPhase(peer, true)
        val secret = ka.generateSecret()
        return { b64 ->
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"),
                IvParameterSpec("0102030405060708".toByteArray())
            )
            String(c.doFinal(android.util.Base64.decode(b64, 0)))
        }
    }

    // ------------------------------------------------------------ wifi p2p -

    private fun transfer(ssid: String, psk: String, goMac: String, port: Int) {
        if (Build.VERSION.SDK_INT < 29) { status("Android <10 不支持定向入群"); return }
        status("加入对端热点 $ssid …")
        val latch = CountDownLatch(1)
        var host: String? = null  // latch provides the happens-before
        val mgr = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        p2pManager = mgr
        val ch = mgr.initialize(this, mainLooper, null)
        p2pChannel = ch
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                val info: WifiP2pInfo? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    val v = i.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    v
                }
                if (info == null || !info.groupFormed) return
                host = info.groupOwnerAddress?.hostAddress
                latch.countDown()
            }
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recv, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(recv, filter)
        }
        try {
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(psk)
                .setDeviceAddress(MacAddress.fromString(goMac))
                .build()
            val ok = CountDownLatch(1)
            mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = ok.countDown()
                override fun onFailure(r: Int) { status("P2P connect 失败 r=$r"); ok.countDown() }
            })
            ok.await(10, TimeUnit.SECONDS)
            if (!latch.await(45, TimeUnit.SECONDS) || host == null) {
                status("入群超时"); return
            }
            status("已入群，握手 $host:$port")
            session(host!!, port, insecureSsl())
        } catch (e: Exception) {
            status("传输失败: ${e.message}")
        } finally {
            try { unregisterReceiver(recv) } catch (_: Exception) {}
            mgr.removeGroup(ch, null)
            p2pChannel = null
        }
    }

    // ------------------------------------------------------ wss + download -

    private fun insecureSsl(): SSLContext {
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), SecureRandom())
        }
    }

    /** Minimal blocking WebSocket over SSLSocket — enough for MTA's small
     *  "type:id:name?json" text frames. */
    private class Ws(host: String, port: Int, ssl: SSLContext) {
        private val sock = ssl.socketFactory.createSocket().apply {
            connect(InetSocketAddress(host, port), 15000)
        } as SSLSocket
        private val inp = sock.inputStream
        private val out = sock.outputStream
        private val rng = SecureRandom()

        init {
            sock.soTimeout = 0
            sock.startHandshake()
            val key = android.util.Base64.encodeToString(
                ByteArray(16).also { rng.nextBytes(it) }, android.util.Base64.NO_WRAP)
            out.write(
                ("GET /websocket HTTP/1.1\r\nHost: $host:$port\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .toByteArray()
            )
            out.flush()
            val head = ByteArrayOutputStream()
            val buf = ByteArray(1)
            while (!head.toString().endsWith("\r\n\r\n")) {
                if (inp.read(buf) < 0) throw EOFException("ws handshake eof")
                head.write(buf)
                if (head.size() > 8192) throw IOException("ws handshake too big")
            }
            if (!head.toString().contains("101"))
                throw IOException("ws refused: ${head.toString().take(120)}")
        }

        fun send(text: String) {
            val p = text.toByteArray()
            val mask = ByteArray(4).also { rng.nextBytes(it) }
            val f = ByteArrayOutputStream()
            f.write(0x81)
            when {
                p.size < 126 -> f.write(0x80 or p.size)
                p.size < 65536 -> {
                    f.write(0x80 or 126)
                    f.write(p.size shr 8); f.write(p.size and 0xFF)
                }
                else -> throw IllegalArgumentException("frame too big")
            }
            f.write(mask)
            f.write(ByteArray(p.size) { (p[it].toInt() xor mask[it % 4].toInt()).toByte() })
            out.write(f.toByteArray()); out.flush()
        }

        fun recv(): String? {
            fun readN(n: Int): ByteArray {
                val b = ByteArray(n); var off = 0
                while (off < n) {
                    val r = inp.read(b, off, n - off)
                    if (r < 0) throw EOFException()
                    off += r
                }
                return b
            }
            while (true) {
                val h = readN(2)
                val op = h[0].toInt() and 0x0F
                var len = h[1].toInt() and 0x7F
                if (len == 126) len = readN(2).let {
                    (it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)
                } else if (len == 127) throw IOException("64-bit frame")
                val masked = h[1].toInt() and 0x80 != 0
                val mask = if (masked) readN(4) else null
                var p = readN(len)
                if (mask != null)
                    p = ByteArray(len) { (p[it].toInt() xor mask[it % 4].toInt()).toByte() }
                when (op) {
                    0x8 -> return null
                    0x9 -> sendRaw(0xA, p)   // ping -> pong
                    0x1, 0x2 -> return String(p, Charsets.UTF_8)
                }
            }
        }

        private fun sendRaw(op: Int, p: ByteArray) {
            val mask = ByteArray(4).also { rng.nextBytes(it) }
            val f = ByteArrayOutputStream()
            f.write(0x80 or op)
            when {
                p.size < 126 -> f.write(0x80 or p.size)
                else -> { f.write(0x80 or 126); f.write(p.size shr 8); f.write(p.size and 0xFF) }
            }
            f.write(mask)
            f.write(ByteArray(p.size) { (p[it].toInt() xor mask[it % 4].toInt()).toByte() })
            out.write(f.toByteArray()); out.flush()
        }

        fun close() = try { sock.close() } catch (_: Exception) {}
    }

    private fun session(host: String, port: Int, ssl: SSLContext) {
        val ws = Ws(host, port, ssl)
        var taskId = ""
        var accepted = false
        try {
            while (true) {
                val raw = ws.recv() ?: break
                val m = Regex("^(\\w+):(\\d+):(\\w+)(\\?(.*))?$").find(raw) ?: continue
                if (m.groupValues[1] != "action") continue
                val id = m.groupValues[2]; val name = m.groupValues[3]
                val payload = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { JSONObject(it) }
                when (name.lowercase()) {
                    "versionnegotiation" -> {
                        val v = minOf(payload?.optInt("version", 1) ?: 1, 1)
                        ws.send("""ack:$id:$name?{"version":$v,"threadLimit":5}""")
                    }
                    "sendrequest" -> {
                        ws.send("ack:$id:$name")
                        if (payload == null) continue
                        taskId = payload.optString("taskId", payload.optString("id", ""))
                        val text = payload.opt("Text") as? String
                        if (text != null) {
                            status("收到文本 ${text.take(40)}")
                            push("收到文本", text.take(80))
                            ws.send("""action:99:status?{"taskId":"$taskId","id":"$taskId","type":1,"reason":"ok"}""")
                            return
                        }
                        status("接收 ${payload.optString("fileName", "file.zip")} …")
                        accepted = true
                    }
                    "status" -> if (payload?.optInt("type") == 3) return
                }
                if (accepted) break   // keep ws open; download + ok happen next
            }
            if (!accepted || taskId.isEmpty()) return
            // pull the zip while the ws session is still alive — the sender
            // keeps it open until we ack the transfer with status:ok
            val url = java.net.URL("https://$host:$port/download?taskId=$taskId")
            val conn = url.openConnection() as HttpsURLConnection
            conn.sslSocketFactory = ssl.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 15000
            val names = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(conn.inputStream.readBytes())).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val safe = File(e.name).name
                    if (safe.isEmpty() || e.isDirectory) continue
                    File(rxDir, safe).writeBytes(z.readBytes())
                    names += safe
                }
            }
            conn.disconnect()
            ws.send("""action:99:status?{"taskId":"$taskId","id":"$taskId","type":1,"reason":"ok"}""")
            status("收到 ${names.size} 个文件: ${names.joinToString()}")
            push("互传完成", "${names.size} 个文件已存到 ${rxDir!!.name}")
        } finally {
            ws.close()
        }
    }

    // -------------------------------------------------------------- infra --

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "互传接收", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("Tandem 互传接收")
            .setContentText("等待设备分享…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(7, n)
    }

    private fun push(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            (System.currentTimeMillis() % 10000).toInt(),
            Notification.Builder(this, CH_ID)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true).build()
        )
    }
}

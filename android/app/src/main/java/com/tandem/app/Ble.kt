package com.tandem.app

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** BLE out-of-band pairing: scan hub → read INFO (endpoints) → write PAIR key. */
object Ble {

    fun dbg(ctx: Context, s: String) {
        try {
            java.io.File(ctx.filesDir, "ble-debug.log")
                .appendText("${System.currentTimeMillis()} $s\n")
        } catch (_: Exception) {}
    }
    fun debugLog(ctx: Context): String =
        java.io.File(ctx.filesDir, "ble-debug.log").let { if (it.exists()) it.readText() else "" }
    fun clearDebugLog(ctx: Context) { java.io.File(ctx.filesDir, "ble-debug.log").delete() }

    private val SERVICE = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private val INFO = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d480")
    private val PAIR = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d481")

    data class HubInfo(val name: String, val port: Int, val ips: List<String>)

    suspend fun pairViaBle(
        ctx: Context,
        deviceName: String,
        timeoutMs: Long = 15_000,
        onState: (String) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("本机无蓝牙")
        check(bt.isEnabled) { "请先开启蓝牙" }
        val scanner = bt.bluetoothLeScanner ?: error("BLE scanner 不可用")

        onState("扫描中枢…"); dbg(ctx, "scan start")
        val device = suspendCancellableCoroutine { cont ->
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                    scanner.stopScan(this)
                    dbg(ctx, "found ${r.device.address}")
                    cont.resume(r.device)
                }
                override fun onScanFailed(code: Int) {
                    dbg(ctx, "scan failed code=$code")
                    cont.resumeWithException(IllegalStateException("BLE 扫描失败 code=$code"))
                }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
            scanner.startScan(listOf(filter), ScanSettings.Builder().build(), cb)
            cont.invokeOnCancellation { scanner.stopScan(cb) }
            Thread {
                Thread.sleep(timeoutMs)
                if (cont.isActive) { scanner.stopScan(cb); cont.resumeWithException(IllegalStateException("未发现中枢")) }
            }.start()
        }

        onState("连接 ${device.address}…")
        var info: HubInfo? = null
        var writeDone = false
        val key = UUID.randomUUID().toString().replace("-", "").take(16)
        var gattRef: BluetoothGatt? = null

        suspendCancellableCoroutine<Unit> { cont ->
            val gcb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newSt: Int) {
                    dbg(ctx, "connChange status=$st newState=$newSt")
                    if (newSt == BluetoothProfile.STATE_CONNECTED) {
                        onState("已连接，发现服务…"); g.discoverServices()
                    }
                    else if (newSt == BluetoothProfile.STATE_DISCONNECTED && cont.isActive)
                        cont.resumeWithException(IllegalStateException("BLE 断开 st=$st"))
                }
                override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
                    dbg(ctx, "services discovered st=$st services=${g.services?.size}")
                    onState("读取中枢信息…")
                    val ch = g.getService(SERVICE)?.getCharacteristic(INFO)
                        ?: return cont.resumeWithException(IllegalStateException("无 INFO 特征"))
                    g.readCharacteristic(ch)
                }
                @Deprecated("deprecated")
                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
                    dbg(ctx, "read st=$st len=${ch.value?.size}")
                    if (st != BluetoothGatt.GATT_SUCCESS)
                        return cont.resumeWithException(IllegalStateException("读 INFO 失败 st=$st"))
                    onState("写入配对请求…")
                    info = parse(String(ch.value ?: ByteArray(0)))
                    val wch = g.getService(SERVICE)?.getCharacteristic(PAIR)
                        ?: return cont.resumeWithException(IllegalStateException("无 PAIR 特征"))
                    val payload = JSONObject().put("k", key).put("n", deviceName).toString().toByteArray()
                    @Suppress("DEPRECATION")
                    wch.value = payload
                    g.writeCharacteristic(wch)
                }
                @Deprecated("deprecated")
                override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
                    dbg(ctx, "write st=$st")
                    writeDone = true
                    g.disconnect()
                    cont.resume(Unit)
                }
            }
            @Suppress("MissingPermission")
            gattRef = if (android.os.Build.VERSION.SDK_INT >= 23)
                device.connectGatt(ctx, false, gcb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(ctx, false, gcb)
            cont.invokeOnCancellation { gattRef?.disconnect(); gattRef?.close() }
            Thread {  // connect+disco+read+write budget
                Thread.sleep(timeoutMs)
                if (cont.isActive) {
                    gattRef?.disconnect(); gattRef?.close()
                    cont.resumeWithException(IllegalStateException("GATT 连接/读写超时"))
                }
            }.start()
        }
        gattRef?.close()
        if (!writeDone) error("写入配对请求失败")
        val hub = info ?: error("未读到中枢信息")

        onState("等待在电脑上批准配对…")
        // pick the first reachable IP, then poll approval on it
        var host: String? = null
        var ca = ""
        for (ip in hub.ips) {
            try { ca = Hub.fetchCa(ip, hub.port); host = ip; break } catch (_: Exception) {}
        }
        if (host == null) error("中枢地址均不可达：${hub.ips.joinToString()} — 试试热点模式")
        pairOverIp(ctx, host, hub.port, key, ca, onState)
    }



    private fun parse(raw: String): HubInfo {
        val j = JSONObject(raw)
        val ips = j.getJSONArray("ips").let { a -> (0 until a.length()).map { a.getString(it) } }
        return HubInfo(j.getString("n"), j.getInt("p"), ips)
    }

    /** POST /api/pair with the BLE key; polls until PC approves or rejects. */
    private fun pairOverIp(ctx: Context, host: String, port: Int, key: String, ca: String, onState: (String) -> Unit) {
        repeat(120) {  // ~3min approval window
            val conn = (URL("https://$host:$port/api/pair").openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = Hub.pinnedFactoryFor(ca)
                hostnameVerifier = Hub.permissiveHostnameVerifier
                requestMethod = "POST"; doOutput = true
                connectTimeout = 4000; readTimeout = 4000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "key=" + java.net.URLEncoder.encode(key, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (conn.responseCode) {
                200 -> {
                    val j = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                    Hub.savePair(ctx, host, port, j.getString("device_token"), j.optString("hub_name", host), ca)
                    return
                }
                202 -> { onState("等待在电脑上批准配对…"); Thread.sleep(1500) }
                else -> error("pair http ${conn.responseCode}")
            }
        }
        error("配对超时：电脑端未批准")
    }
}

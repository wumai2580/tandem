package com.tandem.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

data class Endpoint(val host: String, val port: Int, val name: String,
                    val scheme: String = "https")

object Hub {

    private const val PREFS = "hub"
    private const val SERVICE_TYPE = "_tandem._tcp."

    // ---------- pairing state ----------
    fun paired(ctx: Context): Boolean = prefs(ctx).getString("token", null) != null

    fun endpoint(ctx: Context): Endpoint? {
        val p = prefs(ctx)
        val host = p.getString("host", null) ?: return null
        return Endpoint(host, p.getInt("port", 9787), p.getString("name", "") ?: "",
            p.getString("scheme", "https") ?: "https")
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savePair(ctx: Context, host: String, port: Int, token: String, name: String,
                 caPem: String, scheme: String = "https") {
        prefs(ctx).edit()
            .putString("host", host).putInt("port", port)
            .putString("token", token).putString("name", name)
            .putString("ca", caPem).putString("scheme", scheme).apply()
    }

    fun forget(ctx: Context) = prefs(ctx).edit().clear().apply()

    // ---------- TLS: fetch CA once (TOFU), then pin it ----------
    private fun trustAllFactory(): javax.net.ssl.SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    private val permissiveHostname = HostnameVerifier { _, _ -> true }

    fun fetchCa(host: String, port: Int): String {
        val conn = (URL("https://$host:$port/ca.crt").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = trustAllFactory()
            hostnameVerifier = permissiveHostname
            connectTimeout = 5000; readTimeout = 5000
        }
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun pinnedFactoryFor(caPem: String): javax.net.ssl.SSLSocketFactory = pinnedFactory(caPem)

    val permissiveHostnameVerifier get() = permissiveHostname

    private fun pinnedFactory(caPem: String): javax.net.ssl.SSLSocketFactory {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(caPem.byteInputStream()) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ca", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val base = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        // Also accept a *leaf* equal to the pinned cert — covers hubs that serve
        // their own self-signed cert (Android phone hub) rather than CA-signed.
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
            override fun checkServerTrusted(chain: Array<X509Certificate>?, a: String?) {
                try { base.checkServerTrusted(chain, a); return }
                catch (e: Exception) {
                    if (chain != null && chain.isNotEmpty() &&
                        chain[0].encoded.contentEquals(cert.encoded)) return
                    throw e
                }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
    }

    /** Base URL honoring the stored scheme (http for phone hubs). */
    private fun base(ctx: Context, ep: Endpoint, path: String): URL =
        URL("${ep.scheme}://${ep.host}:${ep.port}$path")

    private fun conn(ctx: Context, url: URL): HttpURLConnection {
        if (url.protocol == "http") {
            return (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 30000
            }
        }
        val ca = prefs(ctx).getString("ca", null)
            ?: throw IllegalStateException("not paired")
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinnedFactory(ca)
            connectTimeout = 8000; readTimeout = 30000
        }
    }

    // ---------- API ----------
    /** Exchange the one-time QR/master token for a permanent device credential. */
    fun pair(ctx: Context, host: String, port: Int, masterToken: String, name: String,
             scheme: String = "https") {
        val ca = if (scheme == "https") fetchCa(host, port) else ""
        val conn = (URL("$scheme://$host:$port/api/pair").openConnection() as HttpURLConnection).apply {
            if (scheme == "https") {
                this as HttpsURLConnection
                sslSocketFactory = pinnedFactory(ca)
                hostnameVerifier = permissiveHostname
            }
            requestMethod = "POST"; doOutput = true
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "token=" + java.net.URLEncoder.encode(masterToken, "UTF-8") +
            "&name=" + java.net.URLEncoder.encode(name, "UTF-8")
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) error("pair failed: ${conn.responseCode}")
        val resp = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val j = org.json.JSONObject(resp)
        savePair(ctx, host, port, j.getString("device_token"), j.optString("hub_name", host), ca, scheme)
        prefs(ctx).edit().putString("device_id", j.getString("device_id")).apply()
    }

    private fun apiToken(ctx: Context) = prefs(ctx).getString("token", "")!!

    /** True on HTTP 401 — means the device credential was revoked. */
    private fun rejected(ctx: Context, code: Int): Boolean {
        if (code == 401) forget(ctx)
        return code == 401
    }

    /** Update the stored host/port after rediscovery; keeps token, CA and name. */
    private fun updateEndpoint(ctx: Context, ep: Endpoint) {
        prefs(ctx).edit().putString("host", ep.host).putInt("port", ep.port)
            .putString("name", ep.name).apply()
    }

    /**
     * Run [block] against the stored endpoint; on a network-level failure,
     * rediscover the hub via mDNS, update the endpoint and retry once.
     */
    private suspend fun <T> withEndpoint(ctx: Context, block: (Endpoint) -> T): T {
        val ep = endpoint(ctx) ?: throw IllegalStateException("not paired")
        try {
            return block(ep)
        } catch (e: java.io.IOException) {
            val fresh = discover(ctx, 3000).firstOrNull() ?: throw e
            updateEndpoint(ctx, fresh)
            return block(fresh)
        }
    }

    fun postText(ctx: Context, text: String) = kotlinx.coroutines.runBlocking {
        withEndpoint(ctx) { ep ->
            val conn = conn(ctx, base(ctx, ep, "/api/text?token=${apiToken(ctx)}")).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "text=" + java.net.URLEncoder.encode(text, "UTF-8") + "&origin=android"
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (rejected(ctx, code)) error("凭证已被吊销，请重新配对")
            if (code != 200) error("send failed: $code")
        }
    }

    fun postFile(ctx: Context, uri: Uri) = kotlinx.coroutines.runBlocking {
        withEndpoint(ctx) { ep ->
            val token = apiToken(ctx)
            val (name, _) = fileMeta(ctx, uri)
            val boundary = "----tandem${System.nanoTime()}"
            val conn = conn(ctx, base(ctx, ep, "/api/files?token=$token")).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setChunkedStreamingMode(1 shl 20)
            }
            conn.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${name}\"\r\n".toByteArray()
                )
                out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(out, 1 shl 20) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
                out.flush()
            }
            val code = conn.responseCode
            if (rejected(ctx, code)) error("凭证已被吊销，请重新配对")
            if (code != 200) error("upload failed: $code")
        }
    }

    private fun fileMeta(ctx: Context, uri: Uri): Pair<String, Long> {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (ni >= 0) c.getString(ni) else null
                val size = if (si >= 0) c.getLong(si) else -1L
                return (name ?: "file") to size
            }
        }
        return "file" to -1L
    }

    // ---------- clipboard ----------
    fun getClipboard(ctx: Context): String = kotlinx.coroutines.runBlocking {
        withEndpoint(ctx) { ep ->
            val conn = conn(ctx, base(ctx, ep, "/api/clipboard?token=${apiToken(ctx)}"))
            org.json.JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                .optString("text", "")
        }
    }

    fun postClipboard(ctx: Context, text: String) = kotlinx.coroutines.runBlocking {
        withEndpoint(ctx) { ep ->
            val conn = conn(ctx, base(ctx, ep, "/api/clipboard?token=${apiToken(ctx)}")).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use {
                it.write(org.json.JSONObject().put("text", text).toString().toByteArray())
            }
            val code = conn.responseCode
            if (rejected(ctx, code)) error("凭证已被吊销，请重新配对")
            if (code != 200) error("clipboard push failed: $code")
        }
    }

    // ---------- inbox items ----------
    fun listItems(ctx: Context): List<org.json.JSONObject> = kotlinx.coroutines.runBlocking {
        withEndpoint(ctx) { ep ->
            val conn = conn(ctx, base(ctx, ep, "/api/items?token=${apiToken(ctx)}"))
            val arr = org.json.JSONArray(
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }
    }

    /** Download an inbox file item; returns raw bytes. */
    fun fetchItem(ctx: Context, itemId: String): ByteArray = kotlinx.coroutines.runBlocking {
        withEndpoint(ctx) { ep ->
            val conn = conn(ctx, base(ctx, ep, "/api/files/$itemId?token=${apiToken(ctx)}"))
            if (conn.responseCode != 200) error("download failed: ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        }
    }

    /**
     * Minimal WebSocket client for /ws — pinned TLS, text frames, JSON events.
     * Runs a reader thread calling [onEvent]; [send] is thread-safe enough for
     * our small payloads. close() ends the loop.
     */
    class Ws private constructor(
        private val sock: java.net.Socket,
        private val onEvent: (org.json.JSONObject) -> Unit
    ) : java.io.Closeable {
        private val inp = sock.inputStream
        private val out = sock.outputStream
        private val rng = java.security.SecureRandom()
        @Volatile var closed = false; private set

        init {
            Thread { readLoop() }.apply { isDaemon = true }.start()
        }

        private fun readLoop() {
            try {
                while (!closed) {
                    val h = readN(2)
                    val op = h[0].toInt() and 0x0F
                    var len = h[1].toInt() and 0x7F
                    if (len == 126) len = readN(2).let {
                        (it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)
                    } else if (len == 127) {
                        val b8 = readN(8)
                        len = ((b8[4].toInt() and 0xFF) shl 24) or
                              ((b8[5].toInt() and 0xFF) shl 16) or
                              ((b8[6].toInt() and 0xFF) shl 8) or (b8[7].toInt() and 0xFF)
                    }
                    val masked = h[1].toInt() and 0x80 != 0
                    val mask = if (masked) readN(4) else null
                    var p = readN(len)
                    if (mask != null)
                        p = ByteArray(len) { (p[it].toInt() xor mask[it % 4].toInt()).toByte() }
                    when (op) {
                        0x8 -> return
                        0x9 -> sendRaw(0xA, p)
                        0x1 -> try { onEvent(org.json.JSONObject(String(p, Charsets.UTF_8))) }
                            catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) { } finally { closed = true }
        }

        private fun readN(n: Int): ByteArray {
            val b = ByteArray(n); var off = 0
            while (off < n) {
                val r = inp.read(b, off, n - off)
                if (r < 0) throw java.io.EOFException()
                off += r
            }
            return b
        }

        fun send(text: String) = sendRaw(0x1, text.toByteArray())

        @Synchronized private fun sendRaw(op: Int, p: ByteArray) {
            val mask = ByteArray(4).also { rng.nextBytes(it) }
            val f = ByteArrayOutputStream()
            f.write(0x80 or op)
            when {
                p.size < 126 -> f.write(0x80 or p.size)
                p.size < 65536 -> {
                    f.write(0x80 or 126); f.write(p.size shr 8); f.write(p.size and 0xFF)
                }
                else -> { f.write(0x80 or 127); f.write(
                    byteArrayOf(0, 0, 0, 0,
                        (p.size shr 24).toByte(), (p.size shr 16).toByte(),
                        (p.size shr 8).toByte(), p.size.toByte())) }
            }
            f.write(mask)
            f.write(ByteArray(p.size) { (p[it].toInt() xor mask[it % 4].toInt()).toByte() })
            out.write(f.toByteArray()); out.flush()
        }

        override fun close() { closed = true; try { sock.close() } catch (_: Exception) {} }

        companion object {
            fun connect(ctx: Context, onEvent: (org.json.JSONObject) -> Unit): Ws {
                val ep = endpoint(ctx) ?: throw IllegalStateException("not paired")
                val sock: java.net.Socket = if (ep.scheme == "http") {
                    java.net.Socket().apply {
                        connect(java.net.InetSocketAddress(ep.host, ep.port), 8000)
                    }
                } else {
                    val ca = prefs(ctx).getString("ca", null) ?: throw IllegalStateException("no ca")
                    (pinnedFactory(ca).createSocket().apply {
                        connect(java.net.InetSocketAddress(ep.host, ep.port), 8000)
                    } as javax.net.ssl.SSLSocket).also { it.startHandshake() }
                }
                val key = android.util.Base64.encodeToString(
                    ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
                    android.util.Base64.NO_WRAP)
                sock.outputStream.write(
                    ("GET /ws?token=${apiToken(ctx)} HTTP/1.1\r\nHost: ${ep.host}:${ep.port}\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n")
                        .toByteArray())
                sock.outputStream.flush()
                val head = ByteArrayOutputStream()
                val b = ByteArray(1)
                while (!head.toString().endsWith("\r\n\r\n")) {
                    if (sock.inputStream.read(b) < 0) throw java.io.EOFException("ws eof")
                    head.write(b)
                    if (head.size() > 8192) throw java.io.IOException("ws head too big")
                }
                if (!head.toString().contains("101"))
                    throw java.io.IOException("ws refused: ${head.toString().take(120)}")
                return Ws(sock, onEvent)
            }
        }
    }

    // ---------- mDNS discovery ----------
    suspend fun discover(ctx: Context, timeoutMs: Long = 4000): List<Endpoint> =
        withContext(Dispatchers.IO) {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("tandem").apply { setReferenceCounted(true); acquire() }
            val found = LinkedHashMap<String, Endpoint>()
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
                    var stopped = false
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(t: String) {}
                        override fun onServiceFound(info: NsdServiceInfo) {
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                                override fun onServiceResolved(i: NsdServiceInfo) {
                                    val host = i.host?.hostAddress ?: return
                                    val name = i.serviceName.substringBefore(".")
                                    found["$host:${i.port}"] = Endpoint(host, i.port, name)
                                }
                            })
                        }
                        override fun onServiceLost(i: NsdServiceInfo) {}
                        override fun onDiscoveryStopped(t: String) {}
                        override fun onStartDiscoveryFailed(t: String, e: Int) {
                            if (!stopped) { stopped = true; cont.resume(Unit) }
                        }
                        override fun onStopDiscoveryFailed(t: String, e: Int) {}
                    }
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                    cont.invokeOnCancellation {
                        stopped = true
                        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
                    }
                    Thread {
                        Thread.sleep(timeoutMs)
                        if (!stopped) {
                            stopped = true
                            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
                            cont.resume(Unit)
                        }
                    }.start()
                }
            } finally {
                lock.release()
            }
            found.values.toList()
        }

    // helper for simple UI probe without coroutines
    fun discoverBlocking(ctx: Context, timeoutMs: Long = 4000): List<Endpoint> =
        kotlinx.coroutines.runBlocking { discover(ctx, timeoutMs) }

    fun readAll(ctx: Context, uri: Uri): ByteArray {
        val buf = ByteArrayOutputStream()
        ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(buf) }
        return buf.toByteArray()
    }

    fun connectivity(ctx: Context): ConnectivityManager =
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}

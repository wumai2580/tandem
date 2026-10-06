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

data class Endpoint(val host: String, val port: Int, val name: String)

object Hub {

    private const val PREFS = "hub"
    private const val SERVICE_TYPE = "_tandem._tcp."

    // ---------- pairing state ----------
    fun paired(ctx: Context): Boolean = prefs(ctx).getString("token", null) != null

    fun endpoint(ctx: Context): Endpoint? {
        val p = prefs(ctx)
        val host = p.getString("host", null) ?: return null
        return Endpoint(host, p.getInt("port", 9787), p.getString("name", "") ?: "")
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savePair(ctx: Context, host: String, port: Int, token: String, name: String, caPem: String) {
        prefs(ctx).edit()
            .putString("host", host).putInt("port", port)
            .putString("token", token).putString("name", name)
            .putString("ca", caPem).apply()
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
        val cert = cf.generateCertificate(caPem.byteInputStream())
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ca", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    private fun https(ctx: Context, url: URL): HttpsURLConnection {
        val ca = prefs(ctx).getString("ca", null)
            ?: throw IllegalStateException("not paired")
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinnedFactory(ca)
            connectTimeout = 8000; readTimeout = 30000
        }
    }

    // ---------- API ----------
    /** Exchange the one-time QR/master token for a permanent device credential. */
    fun pair(ctx: Context, host: String, port: Int, masterToken: String, name: String) {
        val ca = fetchCa(host, port)
        val conn = (URL("https://$host:$port/api/pair").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinnedFactory(ca)
            hostnameVerifier = permissiveHostname
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
        savePair(ctx, host, port, j.getString("device_token"), j.optString("hub_name", host), ca)
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
            val conn = https(ctx, URL("https://${ep.host}:${ep.port}/api/text?token=${apiToken(ctx)}")).apply {
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
            val conn = https(ctx, URL("https://${ep.host}:${ep.port}/api/files?token=$token")).apply {
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

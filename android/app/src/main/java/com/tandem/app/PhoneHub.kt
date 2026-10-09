package com.tandem.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Phone-side Tandem hub: lets another phone/PC pair with THIS phone and push
 * files/text/clipboard to it — the same API surface as tandem/server.py.
 *
 *   HTTPS (self-signed cert in AndroidKeyStore, served as /ca.crt for TOFU)
 *   mDNS _tandem._tcp, QR = https://<lan-ip>:<port>/?token=<master>
 */
class PhoneHubService : Service() {

    companion object {
        const val TAG = "PhoneHub"
        const val PORT = 9787
        var running = false; private set
        var statusCb: ((String) -> Unit)? = null
        fun status(s: String) {
            Log.i(TAG, s)
            android.os.Handler(android.os.Looper.getMainLooper()).post { statusCb?.invoke(s) }
        }
        /** UI hook — inbox/clipboard changed on the phone hub. */
        var onItemsChanged: (() -> Unit)? = null
        fun fireItemsChanged() {
            android.os.Handler(android.os.Looper.getMainLooper()).post { onItemsChanged?.invoke() }
        }
        @Volatile var hub: HubServer? = null
        /** feed a locally-copied clipboard text into the running hub → broadcast to clients */
        fun notifyLocalClip(t: String) { hub?.localClipboard(t) }
    }

    private var server: HubServer? = null
    private var nsdReg: NsdManager.RegistrationListener? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** swiped out of recents on MIUI/ColorOS kills the process — come back in 1s */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val i = Intent(applicationContext, PhoneHubService::class.java)
        val pi = PendingIntent.getService(applicationContext, 12, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pi)
        super.onTaskRemoved(rootIntent)
    }

    /** hub phone copies locally → push to ws clients (only readable while app foreground). */
    private val localClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val c = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val t = if (c != null && c.itemCount > 0)
            c.getItemAt(0).coerceToText(this)?.toString() else null
        if (!t.isNullOrEmpty()) server?.localClipboard(t)
    }

    override fun onBind(i: Intent?): IBinder? = null

    /** restarted with null intent after a kill — hub re-binds prefs itself */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) =
        START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("hub", "Tandem 中枢", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, Notification.Builder(this, "hub")
            .setContentTitle("Tandem 中枢运行中")
            .setContentText("其他设备可以扫码配对并发送内容到本机")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi).build())
        running = true
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tandem:hub")
            .apply { acquire() }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            "tandem:hub").apply { acquire() }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .addPrimaryClipChangedListener(localClipListener)
        Thread { run() }.start()
    }

    private fun run() {
        try {
            val store = HubStore(this)
            server = HubServer(this, store).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                PhoneHubService.hub = it
            }
            status("中枢已启动 $PORT — ${lanIp()}")
            registerNsd()
        } catch (e: Exception) {
            status("中枢启动失败：${e.message}")
            stopSelf()
        }
    }

    private fun registerNsd() {
        val nsd = getSystemService(NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "Tandem-${Build.MODEL}"
            serviceType = "_tandem._tcp."
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { status("mDNS 已注册 ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { status("mDNS 注册失败 $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsdReg = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    override fun onDestroy() {
        running = false
        try { nsdReg?.let { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } }
        catch (_: Exception) {}
        server?.stop()
        PhoneHubService.hub = null
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .removePrimaryClipChangedListener(localClipListener)
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    // ================================================================ store

    class HubStore(ctx: Context) {
        private val dir = File(ctx.filesDir, "hub").apply { mkdirs() }
        private val itemsFile = File(dir, "items.json")
        private val prefs = ctx.getSharedPreferences("phonehub", Context.MODE_PRIVATE)
        private val rng = SecureRandom()
        @Volatile var clipboard = ""
        @Volatile var lastChange = 0L

        val masterToken: String = prefs.getString("token", null)
            ?: ByteArray(16).let { rng.nextBytes(it); it.joinToString("") { b -> "%02x".format(b) } }
                .also { prefs.edit().putString("token", it).apply() }

        fun validToken(t: String?): Boolean {
            if (t.isNullOrEmpty()) return false
            if (t == masterToken) return true
            return issued().let { a -> (0 until a.length()).any { a.getJSONObject(it).optString("token") == t } }
        }

        private fun issued(): JSONArray = try { JSONArray(prefs.getString("devices", "[]")) }
            catch (_: Exception) { JSONArray() }

        fun issueDevice(name: String): JSONObject {
            val id = ByteArray(6).let { rng.nextBytes(it); it.joinToString("") { b -> "%02x".format(b) } }
            val tok = ByteArray(16).let { rng.nextBytes(it); it.joinToString("") { b -> "%02x".format(b) } }
            val d = JSONObject().put("id", id).put("name", name).put("token", tok)
                .put("ts", System.currentTimeMillis() / 1000.0)
            val a = issued().put(d)
            prefs.edit().putString("devices", a.toString()).apply()
            return JSONObject().put("device_id", id).put("device_token", tok)
        }

        fun items(): JSONArray = try { JSONArray(itemsFile.readText()) }
            catch (_: Exception) { JSONArray() }

        private fun add(item: JSONObject): JSONObject {
            val a = items()
            val na = JSONArray()
            na.put(item)
            for (i in 0 until minOf(a.length(), 499)) na.put(a.get(i))
            itemsFile.writeText(na.toString())
            lastChange = System.currentTimeMillis()
            return item
        }

        fun addText(text: String, origin: String): JSONObject = add(JSONObject()
            .put("id", ByteArray(6).let { rng.nextBytes(it); it.joinToString("") { b -> "%02x".format(b) } })
            .put("kind", "text").put("text", text.take(65536))
            .put("ts", System.currentTimeMillis() / 1000.0).put("from", origin))

        fun addFile(name: String, src: File, origin: String): JSONObject {
            val id = ByteArray(6).let { rng.nextBytes(it); it.joinToString("") { b -> "%02x".format(b) } }
            val safe = File(name).name.ifBlank { "file" }
            val dest = File(dir, "${id}_$safe")
            src.renameTo(dest) || run { src.copyTo(dest, true); src.delete() }
            return add(JSONObject().put("id", id).put("kind", "file")
                .put("name", safe).put("size", dest.length())
                .put("path", dest.name)
                .put("ts", System.currentTimeMillis() / 1000.0).put("from", origin))
        }

        fun fileFor(id: String): File? {
            val a = items()
            for (i in 0 until a.length()) {
                val it = a.getJSONObject(i)
                if (it.optString("id") == id && it.optString("kind") == "file") {
                    val f = File(dir, it.optString("path"))
                    if (f.exists()) return f
                }
            }
            return null
        }
    }

    // ================================================================ httpd

    class HubServer(private val ctx: Context, private val store: HubStore) :
        fi.iki.elonen.NanoWSD(PORT) {

        private val wsClients = java.util.concurrent.ConcurrentHashMap.newKeySet<WSock>()

        // HTTP socket read timeout also governs ws lifetime — ping so client
        // pongs keep the connection alive past it.
        private val pinger = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r).apply { isDaemon = true }
        }.also { ex ->
            ex.scheduleAtFixedRate({
                for (c in wsClients) try { c.ping(ByteArray(0)) }
                catch (_: Exception) { wsClients.remove(c) }
            }, 3, 3, java.util.concurrent.TimeUnit.SECONDS)
        }

        override fun stop() { pinger.shutdownNow(); super.stop() }

        /** broadcast a JSON event to all connected /ws clients */
        private fun wsBroadcast(ev: JSONObject) {
            val s = ev.toString()
            for (c in wsClients) {
                try { c.send(s) } catch (_: Exception) { wsClients.remove(c) }
            }
        }

        /** hub phone's own clipboard changed locally — push to all clients. */
        fun localClipboard(t: String) {
            if (t.isEmpty() || t == store.clipboard) return
            store.clipboard = t
            store.lastChange = System.currentTimeMillis()
            wsBroadcast(JSONObject().put("type", "clipboard").put("text", t))
            status("本机复制已广播")
        }

        inner class WSock(handshake: fi.iki.elonen.NanoHTTPD.IHTTPSession) :
            fi.iki.elonen.NanoWSD.WebSocket(handshake) {
            override fun onOpen() { wsClients.add(this) }
            override fun onClose(code: fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode?,
                                 reason: String?, initiatedByRemote: Boolean) {
                wsClients.remove(this)
            }
            override fun onException(e: java.io.IOException?) { wsClients.remove(this) }
            override fun onPong(pong: fi.iki.elonen.NanoWSD.WebSocketFrame?) {}
            override fun onMessage(frame: fi.iki.elonen.NanoWSD.WebSocketFrame?) {
                try {
                    val j = JSONObject(frame?.textPayload ?: return)
                    if (j.optString("type") == "clipboard" && j.optString("text").isNotEmpty()) {
                        store.clipboard = j.getString("text")
                        pasteToClipboard(j.getString("text"))
                        PhoneHubService.fireItemsChanged()
                        for (c in wsClients) if (c != this)
                            try { c.send(j.toString()) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        override fun openWebSocket(handshake: fi.iki.elonen.NanoHTTPD.IHTTPSession) =
            WSock(handshake)

        private fun authed(s: IHTTPSession): Boolean =
            store.validToken(s.parms["token"])

        private fun json(o: Any, st: Response.Status = Response.Status.OK) =
            newFixedLengthResponse(st, "application/json", o.toString())

        override fun serve(s: IHTTPSession): Response {
            val path = s.uri
            PhoneHubService.status("→ ${s.method} $path")
            return try {
                if (path == "/ws" && isWebsocketRequested(s)) {
                    return if (!authed(s)) json(JSONObject().put("detail", "unauthorized"),
                        Response.Status.UNAUTHORIZED)
                    else super.serve(s)   // NanoWSD upgrades + calls openWebSocket
                }
                when {
                    path == "/ca.crt" -> newFixedLengthResponse(
                        Response.Status.OK, "text/plain", "# http hub — no cert\n")
                    path == "/api/info" -> json(JSONObject()
                        .put("name", "Tandem-${Build.MODEL}")
                        .put("version", "0.1.0")
                        .put("url", "http://${lanIp()}:$PORT/"))
                    path == "/api/pair" && s.method == Method.POST -> {
                        val files = HashMap<String, String>()
                        s.parseBody(files)
                        val token = s.parms["token"] ?: ""
                        if (token == store.masterToken) {
                            val cred = store.issueDevice(s.parms["name"] ?: "device")
                            PhoneHubService.status("新设备配对: ${s.parms["name"]}")
                            json(cred.put("hub_name", "Tandem-${Build.MODEL}"))
                        } else json(JSONObject().put("detail", "bad token"), Response.Status.UNAUTHORIZED)
                    }
                    !authed(s) -> json(JSONObject().put("detail", "unauthorized"),
                        Response.Status.UNAUTHORIZED)
                    path == "/api/items" -> json(store.items())
                    path == "/api/text" && s.method == Method.POST -> {
                        val files = HashMap<String, String>()
                        s.parseBody(files)
                        val text = s.parms["text"] ?: ""
                        val origin = s.parms["origin"] ?: ""
                        if (text.isBlank()) json(JSONObject().put("detail", "empty"),
                            Response.Status.BAD_REQUEST)
                        else {
                            if (text.trim().matches(Regex("https?://\\S+"))) openLink(text.trim())
                            val item = store.addText(text, origin)
                            wsBroadcast(JSONObject().put("type", "text").put("item", item))
                            json(item).also { PhoneHubService.fireItemsChanged() }
                        }
                    }
                    path == "/api/files" && s.method == Method.POST -> {
                        val files = HashMap<String, String>()
                        s.parseBody(files)
                        val tmp = files["file"] ?: files.values.firstOrNull()
                            ?: return json(JSONObject().put("detail", "no file"),
                                Response.Status.BAD_REQUEST)
                        val name = s.parms["file"] ?: s.parms["filename"] ?: "file"
                        val origin = s.parms["origin"] ?: ""
                        val item = store.addFile(name, File(tmp), origin)
                        wsBroadcast(JSONObject().put("type", "file").put("item", item))
                        json(item).also { PhoneHubService.fireItemsChanged() }
                    }
                    path.startsWith("/api/files/") -> {
                        val f = store.fileFor(path.removePrefix("/api/files/"))
                            ?: return json(JSONObject().put("detail", "404"),
                                Response.Status.NOT_FOUND)
                        newFixedLengthResponse(Response.Status.OK,
                            "application/octet-stream", f.inputStream(), f.length())
                    }
                    path == "/api/clipboard" && s.method == Method.GET ->
                        json(JSONObject().put("text", store.clipboard))
                    path == "/api/clipboard" && s.method == Method.POST -> {
                        // parseBody decodes application/json as latin-1 — read raw UTF-8 instead
                        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
                        val raw = ByteArray(len)
                        var off = 0
                        while (off < len) {
                            val r = s.inputStream.read(raw, off, len - off)
                            if (r < 0) break; off += r
                        }
                        val text = try { JSONObject(String(raw, Charsets.UTF_8)).optString("text") }
                            catch (_: Exception) { s.parms["text"] ?: "" }
                        if (text.isEmpty()) json(JSONObject().put("detail", "empty"),
                            Response.Status.BAD_REQUEST)
                        else {
                            store.clipboard = text
                            store.lastChange = System.currentTimeMillis()
                            pasteToClipboard(text)
                            wsBroadcast(JSONObject().put("type", "clipboard").put("text", text))
                            PhoneHubService.fireItemsChanged()
                            json(JSONObject().put("ok", true))
                        }
                    }
                    else -> json(JSONObject().put("detail", "not found"),
                        Response.Status.NOT_FOUND)
                }
            } catch (e: Exception) {
                Log.w(TAG, "serve $path", e)
                json(JSONObject().put("detail", e.message ?: "error"),
                    Response.Status.INTERNAL_ERROR)
            }
        }

        private fun openLink(url: String) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }

        private fun pasteToClipboard(text: String) {
            try {
                (ctx.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("tandem", text))
                PhoneHubService.status("剪贴板已写入本机")
            } catch (e: Exception) {
                PhoneHubService.status("收到剪贴板内容（后台无法写入）")
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

fun lanIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress ?: "127.0.0.1"
} catch (_: Exception) { "127.0.0.1" }

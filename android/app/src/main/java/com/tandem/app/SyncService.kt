package com.tandem.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Persistent sync client: keeps /ws connected to the paired hub so clipboard
 * events land on the system clipboard even while the app is backgrounded
 * (a foreground service may still write the clipboard on Android 10+).
 * Reconnects with backoff; dies only via forget/unpair or toggle off.
 */
class SyncService : Service() {

    companion object {
        const val TAG = "TandemSync"
        @Volatile var running = false; private set
        /** last clipboard text pushed BY us or received from hub — echo guard shared with UI */
        @Volatile var lastClip = ""
        var statusCb: ((String) -> Unit)? = null
        /** called on any thread when a remote clipboard event arrives */
        var remoteClipCb: ((String) -> Unit)? = null
        var inboxCb: (() -> Unit)? = null
        fun status(s: String) {
            Log.i(TAG, s)
            android.os.Handler(android.os.Looper.getMainLooper()).post { statusCb?.invoke(s) }
        }
        @Volatile private var active: Hub.Ws? = null
        fun sendClipboard(ctx: android.content.Context, text: String): Boolean {
            val w = active ?: return false
            return try {
                w.send(JSONObject().put("type", "clipboard").put("text", text).toString()); true
            } catch (_: Exception) { false }
        }
    }

    /** fires on every clipboard change; reads only succeed if the ROM allows
     *  background clipboard access (MIUI/ColorOS 剪贴板权限=允许) */
    private val localClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        thread {
            try {
                val t = readClipBestEffort()
                if (t.isNullOrEmpty()) {
                    status("复制发生但读不到（后台禁读）")
                } else if (t != lastClip) {
                    lastClip = t
                    status("本机复制 → 推送")
                    if (!sendClipboard(this, t))
                        try { Hub.postClipboard(this, t) } catch (_: Exception) {}
                }
            } catch (e: Exception) { status("读板失败：${e.message}") }
        }
    }

    @Volatile private var stop = false
    private var loop: Thread? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var focusView: android.view.View? = null

    /**
     * 1×1 overlay, normally NOT_FOCUSABLE so it never steals the keyboard.
     * On clipboard-change we momentarily make it focusable — owning the focused
     * window makes this uid pass the "foreground" clipboard-read check on many
     * ROMs — then restore. Needs overlay permission.
     */
    private fun attachFocusOverlay() {
        if (focusView != null || !Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val v = android.view.View(this)
            val lp = android.view.WindowManager.LayoutParams(
                1, 1,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            wm.addView(v, lp)
            focusView = v
            status("焦点悬浮窗已挂")
        } catch (e: Exception) { status("悬浮窗失败：${e.message}") }
    }

    /** read clipboard; if blocked, briefly flip the overlay to focusable and retry */
    private fun readClipBestEffort(): String? {
        fun read(): String? {
            val c = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip ?: return null
            return if (c.itemCount > 0)
                c.getItemAt(0).coerceToText(this)?.toString() else null
        }
        read()?.let { return it }
        val v = focusView ?: return null
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun focusable(on: Boolean) = main.post {
            try {
                val lp = v.layoutParams as android.view.WindowManager.LayoutParams
                lp.flags = if (on) lp.flags and
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                else lp.flags or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {}
        }
        try {
            focusable(true)
            repeat(10) {
                Thread.sleep(40)
                read()?.let { t -> focusable(false); return t }
            }
        } finally { focusable(false) }
        return null
    }

    /** heads-up notification for incoming clipboard — tap opens app which writes it */
    private fun notifyClip(t: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("clip", "剪贴板同步", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 2,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(41, Notification.Builder(this, "clip")
                .setContentTitle("收到剪贴板内容")
                .setContentText(t.take(80))
                .setStyle(Notification.BigTextStyle().bigText(t))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    private fun detachFocusOverlay() {
        try {
            focusView?.let {
                (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it)
            }
        } catch (_: Exception) {}
        focusView = null
    }

    private fun grabLocks() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tandem:sync")
            .apply { acquire() }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            "tandem:sync").apply { acquire() }
    }

    private fun dropLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null; wifiLock = null
    }

    /** swiped out of recents on MIUI/ColorOS kills the process — come back in 1s */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val i = Intent(applicationContext, SyncService::class.java)
        val pi = PendingIntent.getService(applicationContext, 11, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(i: Intent?): IBinder? = null

    /** restarted with null intent after a kill — keep the loop self-contained */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) =
        START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("sync", "Tandem 同步", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(3, Notification.Builder(this, "sync")
            .setContentTitle("Tandem 同步中")
            .setContentText("剪贴板与文件实时同步已开启")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi).build())
        running = true
        stop = false
        grabLocks()
        attachFocusOverlay()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .addPrimaryClipChangedListener(localClipListener)
        loop = thread { connectLoop() }
    }

    private fun connectLoop() {
        status("同步服务启动")
        while (!stop && Hub.paired(this)) {
            try {
                status("连接中枢…")
                val w = Hub.Ws.connect(this) { ev -> onEvent(ev) }
                if (stop) { w.close(); break }
                active = w
                status("已连接 — 实时同步中")
                // catch up on clipboard events missed while disconnected
                try {
                    val cur = Hub.getClipboard(this)
                    if (cur.isNotEmpty() && cur != lastClip)
                        onEvent(JSONObject().put("type", "clipboard").put("text", cur))
                } catch (_: Exception) {}
                // block until the reader dies (close() or server drop)
                while (!w.closed && !stop) Thread.sleep(500)
            } catch (e: Exception) {
                status("连接失败：${e.message}")
            }
            active = null
            if (!stop) { status("已断开，3 秒后重连"); Thread.sleep(3000) }
        }
        running = false
        status("同步停止")
        stopSelf()
    }

    private fun onEvent(ev: JSONObject) {
        when (ev.optString("type")) {
            "clipboard" -> {
                val t = ev.optString("text")
                if (t.isNotEmpty() && t != lastClip) {
                    lastClip = t
                    val wrote = try {
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("tandem", t))
                        true
                    } catch (_: Exception) { false }
                    status(if (wrote) "剪贴板已同步" else "写剪贴板被拒")
                    // ColorOS silently no-ops background writes — tap-to-apply
                    // notification; opening the app then force-writes it (resume path)
                    notifyClip(t)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        remoteClipCb?.invoke(t)
                    }
                }
            }
            "file", "text", "link" -> android.os.Handler(android.os.Looper.getMainLooper())
                .post { inboxCb?.invoke() }
        }
    }

    override fun onDestroy() {
        stop = true; running = false
        try { active?.close() } catch (_: Exception) {}
        active = null
        dropLocks()
        detachFocusOverlay()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .removePrimaryClipChangedListener(localClipListener)
        super.onDestroy()
    }
}

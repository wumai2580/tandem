package com.tandem.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class ShareActivity : AppCompatActivity() {

    private lateinit var label: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (28 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        label = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, 0) }
        root.addView(label)
        setContentView(root)

        if (!Hub.paired(this)) {
            Toast.makeText(this, "请先在 Tandem 中完成配对", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val ep = Hub.endpoint(this)
        label.text = "发送到 ${ep?.name ?: "PC"}…"

        val uris = mutableListOf<Uri>()
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        when (i.action) {
            Intent.ACTION_SEND -> {
                streamExtra<Uri>(i, Intent.EXTRA_STREAM)?.let(uris::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                (i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: i.extras?.let { e ->
                        @Suppress("DEPRECATION")
                        e.getParcelableArrayList(Intent.EXTRA_STREAM)
                    })?.let(uris::addAll)
            }
        }

        if (uris.isEmpty() && text.isNullOrBlank()) {
            toast("没有可发送的内容"); finish(); return
        }

        thread {
            try {
                for (u in uris) Hub.postFile(this, u)
                if (uris.isEmpty() && !text.isNullOrBlank()) Hub.postText(this, text)
                runOnUiThread {
                    Toast.makeText(this, "已发送到电脑", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    label.text = "发送失败：${e.message}\n确认电脑和手机在同一 Wi-Fi"
                }
            }
        }
    }

    private inline fun <reified T : android.os.Parcelable> streamExtra(i: Intent, key: String): T? =
        if (android.os.Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(key)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

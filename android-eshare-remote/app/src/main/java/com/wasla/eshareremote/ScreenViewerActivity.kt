package com.wasla.eshareremote

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** LAN MJPEG viewer for the paired Wasla TV receiver. */
class ScreenViewerActivity : Activity() {
    private val running = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    private lateinit var status: TextView
    private lateinit var image: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xff050b14.toInt())
            setPadding(16, 16, 16, 16)
        }
        status = TextView(this).apply {
            text = "مشاهدة شاشة التلفاز — MJPEG"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xffeaf4ff.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(48)))
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xff000000.toInt())
        }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this@ScreenViewerActivity).apply {
            text = "إعادة الاتصال"
            isAllCaps = false
            setOnClickListener { startStream() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(Button(this@ScreenViewerActivity).apply {
            text = "إيقاف"
            isAllCaps = false
            setOnClickListener { stopStream(); status.text = "تم إيقاف المشاهدة" }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
        startStream()
    }

    private fun startStream() {
        stopStream()
        val prefs = getSharedPreferences("remote", MODE_PRIVATE)
        val ip = prefs.getString("wasla_tv_ip", "")?.trim().orEmpty()
        val code = prefs.getString("wasla_tv_code", "")?.trim().orEmpty()
        if (ip.isBlank() || code.length != 6) {
            status.text = "امسح رمز اقتران وصلة من التلفاز أولاً"
            return
        }
        running.set(true)
        status.text = "جارٍ الاتصال ببث الشاشة المحلي..."
        Thread({
            var input: BufferedInputStream? = null
            try {
                val url = URL("http://$ip:${SCREEN_PORT}/screen.mjpeg?code=$code")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "multipart/x-mixed-replace")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                connection = conn
                conn.connect()
                if (conn.responseCode != 200) throw IllegalStateException("رفض التلفاز الاتصال (${conn.responseCode})")
                input = BufferedInputStream(conn.inputStream, 64 * 1024)
                runOnUiThread { if (running.get()) status.text = "متصل — بث مباشر عبر MJPEG" }
                while (running.get()) {
                    var line = readAsciiLine(input) ?: break
                    while (line.isEmpty() && running.get()) line = readAsciiLine(input) ?: break
                    if (!running.get()) break
                    if (!line.startsWith("--frame")) continue
                    var contentLength = -1
                    while (true) {
                        val header = readAsciiLine(input) ?: throw IllegalStateException("انتهى تدفق البث")
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) {
                            contentLength = header.substringAfter(':').trim().toIntOrNull() ?: -1
                        }
                    }
                    if (contentLength !in 1..8_000_000) throw IllegalStateException("حجم إطار غير صالح")
                    val bytes = readExactly(input, contentLength)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    runOnUiThread {
                        if (running.get()) image.setImageBitmap(bitmap) else bitmap.recycle()
                    }
                }
                if (running.get()) throw IllegalStateException("انتهى بث الشاشة")
            } catch (e: Exception) {
                if (running.get()) runOnUiThread {
                    status.text = "تعذر عرض الشاشة: ${e.message ?: "تحقق من اتصال الشبكة"}"
                }
            } finally {
                try { input?.close() } catch (_: Exception) { }
                connection?.disconnect()
                connection = null
                running.set(false)
            }
        }, "wasla-mjpeg-viewer").start()
    }

    private fun stopStream() {
        running.set(false)
        try { connection?.disconnect() } catch (_: Exception) { }
        connection = null
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII").trimEnd('\r')
            if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            if (bytes.size() < 4096) bytes.write(value)
        }
    }

    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw java.io.EOFException("إطار JPEG غير مكتمل")
            offset += read
        }
        return bytes
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }

    companion object { private const val SCREEN_PORT = 38473 }
}

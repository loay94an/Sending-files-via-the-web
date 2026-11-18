package com.wasla.eshareremote

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicLong

/** Lightweight 10fps LAN-only MJPEG viewer; WebRTC is used by ScreenViewerActivity remotely. */
class LocalMjpegViewerActivity : Activity() {
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var bitmap: android.graphics.Bitmap? = null
    @Volatile private var userStopped = false
    private var retryCount = 0
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private var retry: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xff050b14.toInt())
            setPadding(12, 12, 12, 12)
        }
        status = TextView(this).apply {
            text = "مشاهدة محلية عبر MJPEG — 10 إطارات/ث"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xffeaf4ff.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(46)))
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this@LocalMjpegViewerActivity).apply {
            text = "إعادة الاتصال"
            isAllCaps = false
            setOnClickListener { retryCount = 0; userStopped = false; startStream() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(Button(this@LocalMjpegViewerActivity).apply {
            text = "إيقاف"
            isAllCaps = false
            setOnClickListener { userStopped = true; stopStream(); status.text = "تم إيقاف المشاهدة" }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))
        setContentView(root)
        startStream()
    }

    private fun startStream() {
        stopStream()
        userStopped = false
        retry?.let { handler.removeCallbacks(it) }
        retry = null
        val prefs = getSharedPreferences("remote", MODE_PRIVATE)
        val ip = prefs.getString("wasla_tv_ip", "")?.trim().orEmpty()
        val code = prefs.getString("wasla_tv_code", "")?.trim().orEmpty()
        if (ip.isBlank() || code.length != 6) {
            status.text = "يلزم اقتران التلفاز على الشبكة المحلية أولاً"
            return
        }
        val session = generation.incrementAndGet()
        running.set(true)
        status.text = "جارٍ الاتصال بالبث المحلي..."
        Thread({
            var input: BufferedInputStream? = null
            var active: HttpURLConnection? = null
            try {
                val conn = (URL("http://$ip:38473/screen.mjpeg?code=$code").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 15000
                    setRequestProperty("Accept", "multipart/x-mixed-replace")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                active = conn
                connection = conn
                conn.connect()
                if (conn.responseCode != 200) throw IllegalStateException("رفض التلفاز الاتصال (${conn.responseCode})")
                input = BufferedInputStream(conn.inputStream, 64 * 1024)
                runOnUiThread { if (generation.get() == session) status.text = "متصل — بث MJPEG محلي" }
                var frames = 0
                var fpsStart = System.currentTimeMillis()
                while (running.get() && generation.get() == session) {
                    var line = readLine(input) ?: break
                    while (line.isEmpty() && running.get()) line = readLine(input) ?: break
                    if (!running.get()) break
                    if (!line.startsWith("--frame")) continue
                    var length = -1
                    while (true) {
                        val header = readLine(input) ?: throw IllegalStateException("انتهى تدفق البث")
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
                    if (length !in 1..8_000_000) throw IllegalStateException("حجم إطار غير صالح")
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        if (count < 0) throw java.io.EOFException("إطار غير مكتمل")
                        offset += count
                    }
                    val frame = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    frames++
                    val now = System.currentTimeMillis()
                    val fps = if (now - fpsStart >= 1000L) {
                        val value = frames * 1000 / (now - fpsStart).coerceAtLeast(1L)
                        frames = 0
                        fpsStart = now
                        value
                    } else -1
                    runOnUiThread {
                        if (running.get() && generation.get() == session) {
                            bitmap?.recycle()
                            bitmap = frame
                            image.setImageBitmap(frame)
                            if (fps >= 0) status.text = "متصل — $fps إطار/ث عبر الشبكة المحلية"
                        } else frame.recycle()
                    }
                }
                if (running.get() && generation.get() == session) throw IllegalStateException("انتهى بث الشاشة")
            } catch (error: Exception) {
                if (running.get() && generation.get() == session) runOnUiThread {
                    status.text = "انقطع البث المحلي: ${error.message ?: "تحقق من الشبكة"}"
                    scheduleRetry()
                }
            } finally {
                try { input?.close() } catch (_: Exception) {}
                active?.disconnect()
                if (connection === active) connection = null
                if (generation.get() == session) running.set(false)
            }
        }, "wasla-local-mjpeg-viewer").start()
    }

    private fun scheduleRetry() {
        if (userStopped || isFinishing || isDestroyed) return
        retry?.let { handler.removeCallbacks(it) }
        val delay = (1000L shl retryCount.coerceAtMost(5)).coerceAtMost(30000L)
        retryCount++
        status.text = "إعادة محاولة البث المحلي خلال ${delay / 1000} ث..."
        val next = Runnable { retry = null; if (!userStopped && !isFinishing && !isDestroyed) startStream() }
        retry = next
        handler.postDelayed(next, delay)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else out.toString("US-ASCII").trimEnd('\r')
            if (value == 10) return out.toString("US-ASCII").trimEnd('\r')
            if (out.size() < 4096) out.write(value)
        }
    }

    private fun stopStream() {
        generation.incrementAndGet()
        running.set(false)
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        userStopped = true
        retry?.let { handler.removeCallbacks(it) }
        retry = null
        stopStream()
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }
}

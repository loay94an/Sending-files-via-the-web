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

/** LAN MJPEG viewer for the paired Wasla TV receiver. */
class ScreenViewerActivity : Activity() {
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var userStopped = false
    private var retryAttempt = 0
    private var retryRunnable: Runnable? = null
    @Volatile private var displayedBitmap: android.graphics.Bitmap? = null
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
            setOnClickListener { retryAttempt = 0; userStopped = false; startStream() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(Button(this@ScreenViewerActivity).apply {
            text = "إيقاف"
            isAllCaps = false
            setOnClickListener { userStopped = true; stopStream(); status.text = "تم إيقاف المشاهدة" }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
        startStream()
    }

    private fun startStream() {
        stopStream()
        userStopped = false
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        val prefs = getSharedPreferences("remote", MODE_PRIVATE)
        val ip = prefs.getString("wasla_tv_ip", "")?.trim().orEmpty()
        val code = prefs.getString("wasla_tv_code", "")?.trim().orEmpty()
        if (ip.isBlank() || code.length != 6) {
            status.text = "بث MJPEG الحالي يعمل داخل الشبكة المحلية فقط. يلزم إكمال مسار WebRTC لمشاهدة التلفاز عبر الإنترنت."
            return
        }
        val session = generation.incrementAndGet()
        running.set(true)
        status.text = "جارٍ الاتصال ببث الشاشة المحلي..."
        Thread({
            var input: BufferedInputStream? = null
            var activeConnection: HttpURLConnection? = null
            try {
                val url = URL("http://$ip:${SCREEN_PORT}/screen.mjpeg?code=$code")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "multipart/x-mixed-replace")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                activeConnection = conn
                connection = conn
                conn.connect()
                if (conn.responseCode != 200) throw IllegalStateException("رفض التلفاز الاتصال (${conn.responseCode})")
                input = BufferedInputStream(conn.inputStream, 64 * 1024)
                runOnUiThread {
                    if (running.get() && generation.get() == session) {
                        retryAttempt = 0
                        status.text = "متصل — بث مباشر عبر MJPEG (الشبكة المحلية)"
                    }
                }
                var frames = 0
                var fpsStarted = System.currentTimeMillis()
                while (running.get() && generation.get() == session) {
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
                    frames++
                    val now = System.currentTimeMillis()
                    val measuredFps = if (now - fpsStarted >= 1000L) {
                        val value = frames * 1000 / (now - fpsStarted).coerceAtLeast(1L)
                        frames = 0
                        fpsStarted = now
                        value
                    } else -1
                    runOnUiThread {
                        if (running.get() && generation.get() == session) {
                            displayedBitmap?.recycle()
                            displayedBitmap = bitmap
                            image.setImageBitmap(bitmap)
                            if (measuredFps >= 0) status.text = "متصل — $measuredFps إطار/ث عبر الشبكة المحلية"
                        } else bitmap.recycle()
                    }
                }
                if (running.get() && generation.get() == session) throw IllegalStateException("انتهى بث الشاشة")
            } catch (e: Exception) {
                if (running.get() && generation.get() == session) runOnUiThread {
                    status.text = "انقطع بث الشاشة: ${e.message ?: "تحقق من اتصال الشبكة"}"
                    scheduleReconnect()
                }
            } finally {
                try { input?.close() } catch (_: Exception) { }
                activeConnection?.disconnect()
                if (connection === activeConnection) connection = null
                if (generation.get() == session) running.set(false)
            }
        }, "wasla-mjpeg-viewer").start()
    }

    private fun scheduleReconnect() {
        if (userStopped || isFinishing || isDestroyed) return
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = (1000L shl retryAttempt.coerceAtMost(5)).coerceAtMost(30000L)
        retryAttempt++
        status.text = "انقطع البث — إعادة المحاولة خلال ${delay / 1000} ث..."
        val retry = Runnable {
            retryRunnable = null
            if (!userStopped && !isFinishing && !isDestroyed) startStream()
        }
        retryRunnable = retry
        mainHandler.postDelayed(retry, delay)
    }

    private fun stopStream() {
        generation.incrementAndGet()
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
        userStopped = true
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        stopStream()
        displayedBitmap?.recycle()
        displayedBitmap = null
        super.onDestroy()
    }

    companion object { private const val SCREEN_PORT = 38473 }
}

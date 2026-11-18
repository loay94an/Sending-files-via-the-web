package com.wasla.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * User-consented, on-device MJPEG screen stream. No cloud encoder or paid streaming
 * service is involved. HTTP is intentionally LAN-only in this first implementation.
 */
class WaslaMjpegServer(
    private val context: Context,
    private val pairingCode: String
) {
    private val active = AtomicBoolean(false)
    private val lastEncodedAt = AtomicLong(0L)
    private val workers = Executors.newCachedThreadPool()
    private val encoder = Executors.newSingleThreadExecutor()
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var httpServer: ServerSocket? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(resultCode: Int, data: Intent): Boolean {
        if (active.get()) return true
        return try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, data) ?: return false
            projection = mediaProjection
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels.coerceIn(320, 3840)
            val height = metrics.heightPixels.coerceIn(240, 2160)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastEncodedAt.get() < 100L) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastEncodedAt.set(now)
                encoder.execute {
                    try {
                        val plane = image.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val buffer = plane.buffer
                        val rgba = IntArray(width * height)
                        for (y in 0 until height) {
                            val rowOffset = y * rowStride
                            for (x in 0 until width) {
                                val offset = rowOffset + x * pixelStride
                                val red = buffer.get(offset).toInt() and 0xff
                                val green = buffer.get(offset + 1).toInt() and 0xff
                                val blue = buffer.get(offset + 2).toInt() and 0xff
                                val alpha = buffer.get(offset + 3).toInt() and 0xff
                                rgba[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                            }
                        }
                        val frame = Bitmap.createBitmap(rgba, width, height, Bitmap.Config.ARGB_8888)
                        val scaledWidth = minOf(width, 1280)
                        val scaledHeight = (height.toFloat() * scaledWidth / width).toInt().coerceAtLeast(1)
                        val scaled = if (scaledWidth == width) frame else Bitmap.createScaledBitmap(frame, scaledWidth, scaledHeight, true)
                        val bytes = java.io.ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 68, bytes)
                        latestJpeg = bytes.toByteArray()
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                    } catch (_: Exception) {
                        // A transient frame conversion failure should not stop capture.
                    } finally {
                        image.close()
                    }
                }
            }, mainHandler)
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stop() }
            }, mainHandler)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "Wasla-MJPEG", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, mainHandler
            )
            active.set(true)
            startHttpServer()
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    private fun startHttpServer() {
        workers.execute {
            try {
                val server = ServerSocket(SCREEN_PORT)
                httpServer = server
                while (active.get()) {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    workers.execute { streamTo(client) }
                }
            } catch (_: Exception) {
                // Socket closure is expected when the user stops screen sharing.
            }
        }
    }

    private fun streamTo(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 8000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                val request = reader.readLine() ?: return
                val path = request.split(' ').getOrNull(1) ?: return
                while (reader.readLine()?.isNotEmpty() == true) { }
                val supplied = try {
                    URLDecoder.decode(path.substringAfter("code=", "").substringBefore("&"), "UTF-8")
                } catch (_: Exception) { "" }
                if (!path.startsWith("/screen.mjpeg") || !constantTimeEquals(supplied, pairingCode)) {
                    val body = "Unauthorized".toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body); flush()
                    }
                    return
                }
                client.soTimeout = 0
                val output = client.getOutputStream()
                output.write(("HTTP/1.1 200 OK\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
                output.flush()
                var lastSent: ByteArray? = null
                while (active.get() && !client.isClosed) {
                    val frame = latestJpeg
                    if (frame != null && frame !== lastSent) {
                        output.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(frame)
                        output.write("\r\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                        lastSent = frame
                    }
                    Thread.sleep(100L)
                }
            } catch (_: Exception) {
                // Client disconnects are normal.
            }
        }
    }

    fun stop() {
        if (!active.getAndSet(false) && projection == null && httpServer == null) return
        try { httpServer?.close() } catch (_: Exception) { }
        httpServer = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null); imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        latestJpeg = null
        encoder.shutdownNow()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object { const val SCREEN_PORT = 38473 }
}

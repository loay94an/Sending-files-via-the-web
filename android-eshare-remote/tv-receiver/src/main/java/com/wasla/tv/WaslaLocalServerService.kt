package com.wasla.tv
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WaslaLocalServerService : Service() {
    private val running = AtomicBoolean(false)
    private val clients = Executors.newCachedThreadPool()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private val discoveryRunning = AtomicBoolean(false)
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var discoveryThread: Thread? = null
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
        startDiscoveryResponder()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServer()
        return START_STICKY
    }
    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                val server = ServerSocket(PORT)
                server.reuseAddress = true
                listener = server
                while (running.get()) {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    clients.execute { handleClient(socket) }
                }
            } catch (_: Exception) {
                running.set(false)
                stopSelf()
            }
        }, "wasla-tv-local-server").apply { isDaemon = true; start() }
    }
    // LAN discovery is deliberately separate from EShare discovery. The phone
    // broadcasts a short probe; this receiver replies with its stable identity and
    // local endpoint, without exposing the pairing PIN in a broadcast packet.
    private fun startDiscoveryResponder() {
        if (!discoveryRunning.compareAndSet(false, true)) return
        discoveryThread = Thread({
            try {
                val socket = DatagramSocket(DISCOVERY_PORT).apply { reuseAddress = true }
                discoverySocket = socket
                val buffer = ByteArray(256)
                while (discoveryRunning.get()) {
                    val request = DatagramPacket(buffer, buffer.size)
                    socket.receive(request)
                    val message = String(request.data, request.offset, request.length, Charsets.UTF_8).trim()
                    if (message != DISCOVERY_REQUEST) continue
                    val response = "WASLA-TV-DISCOVER/1 ${deviceId(this)} $PORT"
                        .toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(response, response.size, request.address, request.port))
                }
            } catch (_: Exception) {
                // Closing the socket during service shutdown is expected.
            } finally {
                discoveryRunning.set(false)
                discoverySocket = null
            }
        }, "wasla-tv-discovery").apply { isDaemon = true; start() }
    }

    private fun handleClient(socket: java.net.Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val hello = reader.readLine() ?: return
            val parts = hello.split(' ', limit = 3)
            if (parts.size != 2 || parts[0] != "PAIR" || !constantTimeEquals(parts[1], pairCode(this))) {
                writer.write("DENIED\n"); writer.flush(); return
            }
            writer.write("OK WASLA-TV/1 $PORT\n"); writer.flush()
            client.soTimeout = 0
            while (running.get() && !client.isClosed) {
                val line = reader.readLine() ?: break
                val response = when {
                    line == "PING" -> "PONG"
                    line == "INFO" -> "WASLA-TV/1 ${deviceId(this)} ${localIpv4()}"
                    line.startsWith("ACTION ") -> executeAction(line.removePrefix("ACTION ").trim())
                    else -> "UNKNOWN_COMMAND"
                }
                writer.write(response + "\n")
                writer.flush()
            }
        }
    }

    /**
     * Executes only a small, explicit command allow-list. Arbitrary shell commands,
     * component names and package launches are intentionally not accepted.
     */
    private fun executeAction(command: String): String {
        val p = command.split(Regex("\\s+"))
        if (p.isEmpty()) return "BAD_ACTION"
        val ok = try {
            when (p[0]) {
                "KEY" -> p.size == 2 && WaslaAccessibilityService.key(p[1])
                "TAP" -> {
                    if (p.size != 3) false else {
                        val nx = p[1].toFloat()
                        val ny = p[2].toFloat()
                        val metrics = resources.displayMetrics
                        if (nx !in 0f..10000f || ny !in 0f..10000f) false
                        else WaslaAccessibilityService.tap(
                            nx / 10000f * metrics.widthPixels,
                            ny / 10000f * metrics.heightPixels
                        )
                    }
                }
                "SWIPE" -> {
                    if (p.size != 6) false else {
                        val nx1 = p[1].toFloat(); val ny1 = p[2].toFloat()
                        val nx2 = p[3].toFloat(); val ny2 = p[4].toFloat()
                        val duration = p[5].toLong()
                        val metrics = resources.displayMetrics
                        val valid = listOf(nx1, ny1, nx2, ny2).all { it in 0f..10000f } &&
                            duration in 80L..2000L
                        valid && WaslaAccessibilityService.swipe(
                            nx1 / 10000f * metrics.widthPixels,
                            ny1 / 10000f * metrics.heightPixels,
                            nx2 / 10000f * metrics.widthPixels,
                            ny2 / 10000f * metrics.heightPixels,
                            duration
                        )
                    }
                }
                "TEXT" -> {
                    if (p.size != 2 || p[1].length > 11000) false else {
                        val bytes = android.util.Base64.decode(p[1], android.util.Base64.NO_WRAP)
                        if (bytes.size > 8192) false
                        else WaslaAccessibilityService.setText(String(bytes, Charsets.UTF_8))
                    }
                }
                else -> false
            }
        } catch (_: Exception) { false }
        return if (ok) "ACTION_OK" else if (!WaslaAccessibilityService.isEnabled()) "ACTION_UNAVAILABLE" else "BAD_ACTION"
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "وصلة - اتصال التلفاز", NotificationManager.IMPORTANCE_LOW))
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL).setContentTitle("وصلة يعمل على التلفاز")
                .setContentText("خادم الاتصال المحلي يستمع على المنفذ $PORT")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("وصلة يعمل على التلفاز")
                .setContentText("خادم الاتصال المحلي يستمع على المنفذ $PORT")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
        }
    }
    override fun onDestroy() {
        running.set(false)
        try { listener?.close() } catch (_: Exception) { }
        try { discoverySocket?.close() } catch (_: Exception) { }
        discoveryRunning.set(false)
        acceptThread?.interrupt()
        discoveryThread?.interrupt()
        clients.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val PORT = 38471
        const val DISCOVERY_PORT = 38470
        const val DISCOVERY_REQUEST = "WASLA_DISCOVER/1"
        private const val CHANNEL = "wasla_tv_server"
        private const val NOTIFICATION_ID = 38471
        private const val PREFS = "wasla_tv_identity"
        fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString("device_id", null)?.let { return it }
            val value = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", value).apply()
            return value
        }
        fun pairCode(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString("pair_code", null)?.let { return it }
            val value = String.format("%06d", SecureRandom().nextInt(1_000_000))
            prefs.edit().putString("pair_code", value).apply()
            return value
        }
        fun localIpv4(): String = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "غير متاح"
        } catch (_: Exception) { "غير متاح" }
        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

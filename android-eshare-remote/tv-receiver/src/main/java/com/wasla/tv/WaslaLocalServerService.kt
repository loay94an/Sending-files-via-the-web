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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WaslaLocalServerService : Service() {
    private val running = AtomicBoolean(false)
    private val clients = Executors.newCachedThreadPool()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
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
                    line == "INFO" -> "WASLA-TV/1 \${deviceId(this)} \${localIpv4()}"
                    line.startsWith("ACTION ") -> "ACTION_RECEIVED"
                    else -> "UNKNOWN_COMMAND"
                }
                writer.write(response + "\n")
                writer.flush()
            }
        }
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
        acceptThread?.interrupt()
        clients.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val PORT = 38471
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

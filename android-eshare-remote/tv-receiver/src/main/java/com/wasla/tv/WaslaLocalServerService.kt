package com.wasla.tv
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.Handler
import android.os.Looper
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
    @Volatile private var fileListener: ServerSocket? = null
    @Volatile private var fileAcceptThread: Thread? = null
    @Volatile private var acceptThread: Thread? = null
    private val discoveryRunning = AtomicBoolean(false)
    private data class PairAttempt(var count: Int = 0, var lockedUntil: Long = 0L)
    private val pairAttempts = mutableMapOf<String, PairAttempt>()

    private fun verifyPair(socket: java.net.Socket, supplied: String): Boolean {
        val address = socket.inetAddress?.hostAddress ?: "unknown"
        synchronized(pairAttempts) {
            val now = System.currentTimeMillis()
            val state = pairAttempts.getOrPut(address) { PairAttempt() }
            if (state.lockedUntil > now) return false
            val valid = constantTimeEquals(supplied, pairCode(this))
            if (valid) {
                pairAttempts.remove(address)
                return true
            }
            state.count++
            if (state.count >= 8) {
                state.count = 0
                state.lockedUntil = now + 60_000L
            }
            return false
        }
    }
    @Volatile private var discoverySocket: DatagramSocket? = null
    private val relayClient = OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
    @Volatile private var relaySocket: WebSocket? = null
    private val relayHandler = Handler(Looper.getMainLooper())
    private val relayReconnect = Runnable { connectRelayIfConfigured() }
    @Volatile private var discoveryThread: Thread? = null
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
        startFileServer()
        startDiscoveryResponder()
        connectRelayIfConfigured()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServer()
        if (intent?.action == ACTION_RECONNECT_RELAY) {
            try { relaySocket?.close(1000, "Relay settings changed") } catch (_: Exception) { }
            relaySocket = null
        }
        connectRelayIfConfigured()
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

    // Separate binary file channel keeps file traffic away from latency-sensitive
    // remote-control commands. The TV owner selects an allowed folder via SAF.
    private fun startFileServer() {
        if (fileListener != null) return
        fileAcceptThread = Thread({
            try {
                val server = ServerSocket(FILE_PORT)
                server.reuseAddress = true
                fileListener = server
                while (running.get()) {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    clients.execute { handleFileClient(socket) }
                }
            } catch (_: Exception) {
                // Closed during shutdown or receiver restart.
            }
        }, "wasla-tv-file-server").apply { isDaemon = true; start() }
    }


    private fun connectRelayIfConfigured() {
        if (!running.get() || relaySocket != null) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = prefs.getString("relay_base_url", null)?.trim()?.trimEnd('/') ?: return
        if (base.isBlank()) return
        val wsBase = when {
            base.startsWith("https://", true) -> "wss://" + base.substringAfter("://")
            base.startsWith("wss://", true) -> base
            else -> return
        }
        val url = "$wsBase/connect/${deviceId(this)}"
        val request = try {
            Request.Builder().url(url)
                .header("X-Wasla-Role", "tv")
                .header("X-Wasla-Pairing-Code", pairCode(this))
                .build()
        } catch (_: Exception) { return }
        relaySocket = relayClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    if (message.optString("type") == "action") {
                        val id = message.optString("id")
                        val result = executeAction(message.optString("command"))
                        webSocket.send(JSONObject().put("type", "action_result").put("id", id).put("result", result).toString())
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (relaySocket === webSocket) relaySocket = null
                if (running.get()) relayHandler.postDelayed(relayReconnect, 5000L)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (relaySocket === webSocket) relaySocket = null
                if (running.get()) relayHandler.postDelayed(relayReconnect, 5000L)
            }
        })
    }

    private fun selectedFolder(): DocumentFile? {
        val raw = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("shared_tree_uri", null)
            ?: return null
        return try { DocumentFile.fromTreeUri(this, Uri.parse(raw)) } catch (_: Exception) { null }
    }

    private fun findSharedDocument(uri: Uri, folder: DocumentFile, depth: Int = 0, budget: IntArray = intArrayOf(0)): DocumentFile? {
        if (depth > 8 || budget[0]++ > 1200) return null
        if (folder.uri == uri) return folder
        if (!folder.isDirectory) return null
        for (child in folder.listFiles()) {
            if (child.uri == uri) return child
            if (child.isDirectory) findSharedDocument(uri, child, depth + 1, budget)?.let { return it }
        }
        return null
    }

    private fun findParentOf(uri: Uri, folder: DocumentFile, depth: Int = 0): DocumentFile? {
        if (depth > 8 || !folder.isDirectory) return null
        for (child in folder.listFiles()) {
            if (child.uri == uri) return folder
            if (child.isDirectory) findParentOf(uri, child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun handleFileClient(socket: java.net.Socket) {
        socket.use { client ->
            client.soTimeout = 120_000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            val hello = try { input.readUTF() } catch (_: Exception) { return }
            if (!hello.startsWith("PAIR ") || !verifyPair(client, hello.removePrefix("PAIR "))) {
                output.writeUTF("DENIED"); output.flush(); return
            }
            output.writeUTF("OK WASLA-FILES/1"); output.flush()
            try {
                val command = input.readUTF()
                when {
                    command == "LIST" || command.startsWith("LIST ") -> {
                        val root = selectedFolder()
                        val requested = command.removePrefix("LIST").trim()
                        val current = if (requested.isBlank()) root else try {
                            val uri = Uri.parse(String(Base64.decode(requested, Base64.NO_WRAP), Charsets.UTF_8))
                            root?.let { findSharedDocument(uri, it) }
                        } catch (_: Exception) { null }
                        if (root == null || !root.canRead()) output.writeUTF("ERROR FOLDER_NOT_CONFIGURED")
                        else if (current == null || !current.isDirectory || !current.canRead()) output.writeUTF("ERROR DIRECTORY_NOT_FOUND")
                        else {
                            val rows = JSONArray()
                            current.listFiles().take(150).forEach { doc ->
                                rows.put(JSONObject().apply {
                                    put("id", Base64.encodeToString(doc.uri.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                                    put("name", doc.name ?: "ملف")
                                    put("mime", doc.type ?: "application/octet-stream")
                                    put("size", doc.length())
                                    put("directory", doc.isDirectory)
                                })
                            }
                            val encoded = Base64.encodeToString(rows.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            output.writeUTF("FILES $encoded")
                        }
                    }
                    else -> {
                        val parts = command.split(' ')
                        when (parts.firstOrNull()) {
                            "PUT" -> {
                                if (parts.size != 5) {
                                    output.writeUTF("ERROR BAD_REQUEST")
                                } else {
                                    val directory = parts[1]
                                    val name = String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8)
                                    val mime = String(Base64.decode(parts[3], Base64.NO_WRAP), Charsets.UTF_8)
                                    val size = parts[4].toLongOrNull()
                                    val root = selectedFolder()
                                    val targetFolder = if (directory == "ROOT") root else try {
                                        val uri = Uri.parse(String(Base64.decode(directory, Base64.NO_WRAP), Charsets.UTF_8))
                                        root?.let { findSharedDocument(uri, it) }
                                    } catch (_: Exception) { null }
                                    if (targetFolder == null || !targetFolder.isDirectory || !targetFolder.canWrite()) output.writeUTF("ERROR FOLDER_NOT_WRITABLE")
                                    else if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.length > 240 ||
                                        size == null || size !in 0L..MAX_FILE_BYTES) output.writeUTF("ERROR INVALID_FILE")
                                    else if (targetFolder.findFile(name) != null) output.writeUTF("ERROR FILE_EXISTS")
                                    else {
                                        val created = targetFolder.createFile(mime.ifBlank { "application/octet-stream" }, name)
                                        if (created == null) output.writeUTF("ERROR CREATE_FAILED")
                                        else {
                                            val target = contentResolver.openOutputStream(created.uri, "w")
                                            if (target == null) {
                                                created.delete()
                                                output.writeUTF("ERROR OPEN_FAILED")
                                            } else {
                                                try {
                                                    target.use { stream ->
                                                        val buffer = ByteArray(32 * 1024)
                                                        var remaining = size
                                                        while (remaining > 0) {
                                                            val count = minOf(buffer.size.toLong(), remaining).toInt()
                                                            input.readFully(buffer, 0, count)
                                                            stream.write(buffer, 0, count)
                                                            remaining -= count
                                                        }
                                                        stream.flush()
                                                    }
                                                    output.writeUTF("STORED")
                                                } catch (e: Exception) {
                                                    created.delete()
                                                    throw e
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "GET" -> {
                                if (parts.size != 2) output.writeUTF("ERROR BAD_REQUEST")
                                else {
                                    val uriText = String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8)
                                    val uri = Uri.parse(uriText)
                                    val doc = selectedFolder()?.let { findSharedDocument(uri, it) }
                                    val size = doc?.length() ?: -1L
                                    val stream = if (doc?.canRead() == true && !doc.isDirectory && size in 0L..MAX_FILE_BYTES) contentResolver.openInputStream(doc.uri) else null
                                    if (doc == null || stream == null) output.writeUTF("ERROR NOT_READABLE")
                                    else {
                                        val mime64 = Base64.encodeToString((doc.type ?: "application/octet-stream").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                        val name64 = Base64.encodeToString((doc.name ?: "download.bin").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                        output.writeUTF("DATA $size $mime64 $name64")
                                        stream.use { src ->
                                            val buffer = ByteArray(32 * 1024)
                                            var remaining = size
                                            while (remaining > 0) {
                                                val count = src.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                                if (count < 0) throw java.io.EOFException("File shortened during download")
                                                output.write(buffer, 0, count)
                                                remaining -= count
                                            }
                                        }
                                        output.flush()
                                    }
                                }
                            }
                            "DELETE" -> {
                                if (parts.size != 2) output.writeUTF("ERROR BAD_REQUEST")
                                else {
                                    val uriText = String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8)
                                    val uri = Uri.parse(uriText)
                                    val doc = selectedFolder()?.let { findSharedDocument(uri, it) }
                                    output.writeUTF(if (doc?.canWrite() == true && !doc.isDirectory && doc.delete()) "DELETED" else "ERROR DELETE_FAILED")
                                }
                            }
                            "MKDIR" -> {
                                if (parts.size != 3) output.writeUTF("ERROR BAD_REQUEST")
                                else {
                                    val uri = Uri.parse(String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8))
                                    val name = String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8)
                                    val root = selectedFolder()
                                    val parent = if (parts[1] == "ROOT") root else root?.let { findSharedDocument(uri, it) }
                                    val valid = name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && name.length <= 120
                                    output.writeUTF(when {
                                        parent == null || !parent.isDirectory || !parent.canWrite() -> "ERROR FOLDER_NOT_WRITABLE"
                                        !valid -> "ERROR INVALID_NAME"
                                        parent.findFile(name) != null -> "ERROR NAME_EXISTS"
                                        parent.createDirectory(name) != null -> "CREATED"
                                        else -> "ERROR CREATE_FAILED"
                                    })
                                }
                            }
                            "RENAME" -> {
                                if (parts.size != 3) output.writeUTF("ERROR BAD_REQUEST")
                                else {
                                    val uri = Uri.parse(String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8))
                                    val name = String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8)
                                    val root = selectedFolder()
                                    val doc = root?.let { findSharedDocument(uri, it) }
                                    val parent = root?.let { findParentOf(uri, it) }
                                    val valid = name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && name.length <= 240
                                    output.writeUTF(if (valid && doc?.canWrite() == true && parent?.findFile(name) == null && doc.renameTo(name)) "RENAMED" else "ERROR RENAME_FAILED")
                                }
                            }
                            else -> output.writeUTF("ERROR UNKNOWN_COMMAND")
                        }
                    }
                }
            } catch (_: Exception) {
                try { output.writeUTF("ERROR TRANSFER_FAILED"); output.flush() } catch (_: Exception) { }
            }
            output.flush()
        }
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
            if (parts.size != 2 || parts[0] != "PAIR" || !verifyPair(client, parts[1])) {
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
                "MOVE" -> {
                    if (p.size != 3) false else {
                        val nx = p[1].toFloat()
                        val ny = p[2].toFloat()
                        val metrics = resources.displayMetrics
                        nx in 0f..10000f && ny in 0f..10000f &&
                            WaslaAccessibilityService.movePointer(
                                nx / 10000f * metrics.widthPixels,
                                ny / 10000f * metrics.heightPixels
                            )
                    }
                }
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
                "LONG_PRESS" -> {
                    if (p.size != 4) false else {
                        val nx = p[1].toFloat(); val ny = p[2].toFloat()
                        val duration = p[3].toLong()
                        val metrics = resources.displayMetrics
                        nx in 0f..10000f && ny in 0f..10000f && duration in 500L..2000L &&
                            WaslaAccessibilityService.longPress(
                                nx / 10000f * metrics.widthPixels,
                                ny / 10000f * metrics.heightPixels,
                                duration
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
        try { fileListener?.close() } catch (_: Exception) { }
        try { discoverySocket?.close() } catch (_: Exception) { }
        discoveryRunning.set(false)
        acceptThread?.interrupt()
        discoveryThread?.interrupt()
        relayHandler.removeCallbacks(relayReconnect)
        try { relaySocket?.close(1000, "TV receiver stopped") } catch (_: Exception) { }
        relaySocket = null
        relayClient.dispatcher.executorService.shutdown()
        relayClient.connectionPool.evictAll()
        clients.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val PORT = 38471
        const val DISCOVERY_PORT = 38470
        const val FILE_PORT = 38472
        const val ACTION_RECONNECT_RELAY = "com.wasla.tv.RECONNECT_RELAY"
        private const val MAX_FILE_BYTES = 536870912L
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

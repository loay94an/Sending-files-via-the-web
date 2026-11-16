package com.wasla.eshareremote

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var tcp: Socket? = null
    private var output: OutputStream? = null
    private var discoveryLock: WifiManager.MulticastLock? = null
    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null
    private var airMouseEnabled = false
    private var lastGyroTimestamp = 0L
    @Volatile private var lastAirSendAt = 0L
    private val gyroSensitivity = 1.8f
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private val prefs by lazy { getSharedPreferences("remote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff0b1220.toInt()
        window.navigationBarColor = 0xff0b1220.toInt()
        val isAndroidTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("android.hardware.type.television")
        if (isAndroidTv) {
            startActivity(Intent(this, TvNetworkSetupActivity::class.java))
            finish()
            return
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        buildUi()
        val saved = prefs.getString("ip", "") ?: ""
        ipInput.setText(saved)
        discoverAndConnect()
    }

    private fun buildUi() {
        val bg = 0xff080f1e.toInt()
        val surface = 0xff111c30.toInt()
        val accent = 0xff19c6b4.toInt()
        val muted = 0xff8fa3bd.toInt()

        fun rounded(color: Int, radius: Int = 18, stroke: Int = 0): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != 0) setStroke(dp(1), stroke)
            }

        fun label(value: String, size: Float = 12f, color: Int = 0xffe7eef8.toInt(), bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER_VERTICAL
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        fun makeButton(value: String, primary: Boolean = false, height: Int = 42): Button =
            Button(this).apply {
                text = value
                textSize = 12f
                isAllCaps = false
                minHeight = dp(height)
                setPadding(dp(6), 0, dp(6), 0)
                setTextColor(if (primary) 0xff061521.toInt() else 0xffeaf2ff.toInt())
                background = rounded(if (primary) accent else 0xff1d2b43.toInt(), 14, if (primary) 0 else 0xff2c3d58.toInt())
                stateListAnimator = null
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(7), dp(12), dp(8))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val brand = TextView(this).apply {
            text = "وِصلة"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xfff4f8ff.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(38), 1f))
        status = label("جارٍ البحث عن التلفاز...", 10f, 0xffa9f3e8.toInt())
        status.gravity = Gravity.CENTER
        status.maxLines = 1
        header.addView(status, LinearLayout.LayoutParams(0, dp(38), 2f))
        root.addView(header, lp(-1, dp(38)))

        val connection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        ipInput = EditText(this).apply {
            hint = "عنوان IP للتلفاز"
            textSize = 13f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setTextColor(0xfff8fafc.toInt())
            setHintTextColor(muted)
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(0xff0b1527.toInt(), 12, 0xff30415d.toInt())
        }
        connection.addView(ipInput, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(7) })
        connectButton = makeButton("اتصال", true, 40).apply {
            setOnClickListener { discoverAndConnect(ipInput.text.toString().trim().ifEmpty { null }) }
        }
        connection.addView(connectButton, LinearLayout.LayoutParams(dp(82), dp(40)))
        root.addView(connection, lp(-1, dp(40)))

        val airToggle = makeButton("تشغيل الماوس الهوائي", true, 40).apply {
            isEnabled = gyroSensor != null
            if (gyroSensor == null) text = "مستشعر الحركة غير متاح"
            setOnClickListener {
                airMouseEnabled = !airMouseEnabled
                text = if (airMouseEnabled) "إيقاف الماوس الهوائي" else "تشغيل الماوس الهوائي"
                if (airMouseEnabled) startAirMouse() else stopAirMouse()
                setStatus(if (airMouseEnabled) "الماوس الهوائي يعمل" else "التحكم العادي")
            }
        }
        root.addView(airToggle, lp(-1, dp(40)))

        val navArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        fun navButton(symbol: String, key: Int?, size: Int, primary: Boolean = false): Button =
            Button(this).apply {
                text = symbol
                textSize = if (primary) 23f else 20f
                isAllCaps = false
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setTextColor(if (primary) 0xff061521.toInt() else 0xffeaf2ff.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (primary) accent else 0xff1d2b43.toInt())
                    if (!primary) setStroke(dp(1), 0xff38506e.toInt())
                }
                stateListAnimator = null
                if (key != null) setOnClickListener { sendKey(key) }
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                    setMargins(dp(5), dp(3), dp(5), dp(3))
                }
            }

        fun navRow(left: Button, center: Button, right: Button) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                addView(left)
                addView(center)
                addView(right)
            }

        navArea.addView(navRow(navButton("", null, 58), navButton("▲", 19, 58), navButton("", null, 58)))
        navArea.addView(navRow(navButton("◀", 21, 58), navButton("OK", 23, 78, true), navButton("▶", 22, 58)))
        navArea.addView(navRow(navButton("", null, 58), navButton("▼", 20, 58), navButton("", null, 58)))
        root.addView(navArea, LinearLayout.LayoutParams(-1, 0, 1f))

        val touchTitle = label("لوحة اللمس — في الوضع الهوائي: اسحب للتنقل وانقر للاختيار", 10f, muted)
        touchTitle.gravity = Gravity.CENTER
        root.addView(touchTitle, lp(-1, dp(25)))
        val touchPad = FrameLayout(this).apply {
            background = rounded(surface, 15, 0xff30415d.toInt())
            contentDescription = "لوحة اللمس"
        }
        val touchHint = label("↑  ↓  ←  →     •     نقرة = اختيار", 12f, 0xffd7e3f3.toInt(), true)
        touchHint.gravity = Gravity.CENTER
        touchPad.addView(touchHint, FrameLayout.LayoutParams(-1, -1))
        var downX = 0f
        var downY = 0f
        var moved = false
        touchPad.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) > dp(18)) moved = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (airMouseEnabled) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (!moved) sendKey(23)
                        else if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) sendKey(if (dy < 0) 19 else 20)
                        else sendKey(if (dx < 0) 21 else 22)
                    }
                    view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        root.addView(touchPad, lp(-1, dp(64)))

        val saved = prefs.getString("ip", "") ?: ""
        ipInput.setText(saved)
        setContentView(root)
    }

    private fun addSectionLabel(parent: LinearLayout, label: String) {
        val view = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xff5eead4.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(1))
        }
        parent.addView(view, lp(-1, dp(22)))
    }

    private fun compactRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private fun compactWeighted() = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
        setMargins(dp(2), dp(1), dp(2), dp(1))
    }

    private fun buttonRow(left: String, middle: String, right: String, leftKey: Int?, midKey: Int, rightKey: Int?): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            // Keep physical left/right positions stable inside the Arabic RTL screen.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        row.addView(if (leftKey == null) spacer() else actionButton(left, leftKey), weighted())
        row.addView(actionButton(middle, midKey, true), weighted())
        row.addView(if (rightKey == null) spacer() else actionButton(right, rightKey), weighted())
        return row
    }

    private fun spacer(): View = View(this).apply { minimumHeight = dp(40) }
    private fun actionButton(label: String, key: Int, primary: Boolean = false): Button =
        Button(this).apply {
            text = label
            textSize = if (primary) 15f else 12f
            isAllCaps = false
            minHeight = dp(if (primary) 48 else 42)
            setPadding(dp(6), 0, dp(6), 0)
            setTextColor(if (primary) 0xff061521.toInt() else 0xffeaf2ff.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (primary) 0xff19c6b4.toInt() else 0xff1d2b43.toInt())
                cornerRadius = dp(14).toFloat()
                if (!primary) setStroke(dp(1), 0xff2c3d58.toInt())
            }
            stateListAnimator = null
            setOnClickListener { sendKey(key) }
        }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply { bottomMargin = dp(3) }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(message: String) = runOnUiThread { status.text = message }

    private fun discoverAndConnect(manualIp: String? = null) {
        connectButton.isEnabled = false
        setStatus("جارٍ اكتشاف التلفاز عبر الشبكة...")
        io.execute {
            try {
                closeConnection()
                val found = if (manualIp != null) Device("التلفاز", manualIp, discoverPort(manualIp)) else discover()
                connect(found)
            } catch (e: Exception) {
                setStatus("تعذر الاتصال: ${e.message ?: "تحقق من Wi-Fi"}")
            } finally {
                try { discoveryLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
                discoveryLock = null
                runOnUiThread { connectButton.isEnabled = true }
            }
        }
    }

    private data class Device(val name: String, val ip: String, val port: Int)

    private fun packet(): ByteArray {
        val name = "FindECloudBox".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(50).order(ByteOrder.BIG_ENDIAN)
            .putInt(0).putInt(0).putInt(0).putInt(name.size).putInt(0).put(name).array()
    }

    @Suppress("DEPRECATION")
    private fun discover(): Device {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo
        try {
            discoveryLock?.let { if (it.isHeld) it.release() }
            discoveryLock = wifi.createMulticastLock("wasla-eshare-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }

        val saved = prefs.getString("ip", null)
        if (!saved.isNullOrBlank() && isControlPortOpen(saved, 8121)) {
            return Device("التلفاز المحفوظ", saved, 8121)
        }

        data class Subnet(val local: InetAddress, val broadcast: InetAddress, val prefix: Int)
        val subnets = mutableListOf<Subnet>()
        if (dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            try {
                val local = InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dhcp.ipAddress).array())
                val mask = dhcp.netmask
                val prefix = Integer.bitCount(mask)
                val b = (dhcp.ipAddress and mask) or mask.inv()
                val broadcast = InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b).array())
                subnets.add(Subnet(local, broadcast, prefix))
            } catch (_: Exception) { }
        }

        // Android hotspot interfaces are often absent from WifiManager.dhcpInfo.
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .sortedByDescending { it.name.contains("wlan", true) || it.name.contains("ap", true) || it.name.contains("wifi", true) }
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (entry in networkInterface.interfaceAddresses) {
                    val local = entry.address
                    val broadcast = entry.broadcast
                    if (local !is java.net.Inet4Address || local.isLoopbackAddress || broadcast == null) continue
                    if (subnets.none { it.local.hostAddress == local.hostAddress }) {
                        subnets.add(Subnet(local, broadcast, entry.networkPrefixLength.toInt()))
                    }
                }
            }
        } catch (_: Exception) { }

        // Broadcast discovery through every active IPv4 interface, including phone hotspot.
        try {
            val bytes = packet()
            val udp = DatagramSocket(null)
            udp.reuseAddress = true
            udp.bind(InetSocketAddress(48689))
            udp.broadcast = true
            udp.soTimeout = 500
            try {
                val targets = (subnets.map { it.broadcast } + InetAddress.getByName("255.255.255.255"))
                    .distinctBy { it.hostAddress }
                for (target in targets) {
                    try { udp.send(DatagramPacket(bytes, bytes.size, target, 48689)) } catch (_: Exception) { }
                }
                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 3500
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        udp.receive(response)
                        val text = String(response.data, 0, response.length, Charsets.UTF_8)
                        if (text.startsWith("ECloudBox")) {
                            val fields = text.split(":")
                            if (fields.size >= 3) {
                                val port = fields[2].toIntOrNull()
                                val ip = response.address.hostAddress
                                if (port != null && !ip.isNullOrBlank()) {
                                    val device = Device(fields.getOrElse(1) { "EShare" }, ip, port)
                                    prefs.edit().putString("ip", ip).apply()
                                    runOnUiThread { ipInput.setText(ip) }
                                    return device
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            } finally {
                udp.close()
            }
        } catch (_: Exception) { }

        setStatus("لم يرد اكتشاف UDP؛ جارٍ فحص عناوين شبكة الهاتف ونقطة الاتصال...")
        val found = java.util.concurrent.atomic.AtomicReference<Device?>(null)
        val pool = Executors.newFixedThreadPool(48)
        val tasks = mutableListOf<java.util.concurrent.Future<*>>()
        for (subnet in subnets) {
            val prefix = subnet.prefix.coerceIn(1, 30).let { if (it < 23) 24 else it }
            val local = ByteBuffer.wrap(subnet.local.address).order(ByteOrder.LITTLE_ENDIAN).int
            val mask = -1 shl (32 - prefix)
            val network = local and mask
            val broadcastIp = network or mask.inv()
            for (host in (network + 1) until broadcastIp) {
                if (host == local || found.get() != null) continue
                val ip = listOf(host and 255, (host ushr 8) and 255, (host ushr 16) and 255, (host ushr 24) and 255).joinToString(".")
                tasks.add(pool.submit {
                    if (found.get() == null && isControlPortOpen(ip, 8121)) {
                        found.compareAndSet(null, Device("تلفاز EShare", ip, 8121))
                    }
                })
            }
        }
        val deadline = System.currentTimeMillis() + 9000
        while (System.currentTimeMillis() < deadline && found.get() == null && tasks.any { !it.isDone }) {
            Thread.sleep(80)
        }
        pool.shutdownNow()
        found.get()?.let { device ->
            prefs.edit().putString("ip", device.ip).apply()
            runOnUiThread { ipInput.setText(device.ip) }
            return device
        }
        throw IllegalStateException("لم يتم العثور على تلفاز EShare. تأكد أن التلفاز متصل بنقطة اتصال الهاتف، أو أدخل عنوان IP الظاهر للتلفاز يدوياً.")
    }

    private fun isControlPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 450)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun discoverPort(ip: String): Int {
        // Some EShare firmware accepts TCP control on 8121 but does not answer
        // a directed UDP discovery packet. Keep manual-IP connection usable.
        return try {
            DatagramSocket().use { udp ->
                udp.soTimeout = 1200
                val bytes = packet()
                udp.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), 48689))
                val buffer = ByteArray(1024)
                val response = DatagramPacket(buffer, buffer.size)
                udp.receive(response)
                val text = String(response.data, 0, response.length, Charsets.UTF_8)
                val fields = text.split(":")
                if (text.startsWith("ECloudBox") && fields.size >= 4) {
                    fields[2].toIntOrNull() ?: 8121
                } else 8121
            }
        } catch (_: Exception) {
            8121
        }
    }

    private fun connect(device: Device) {
        val socket = Socket()
        socket.connect(InetSocketAddress(device.ip, device.port), 4000)
        tcp = socket
        output = socket.getOutputStream()
        prefs.edit().putString("ip", device.ip).apply()
        setStatus("متصل: ${device.name} — ${device.ip}:${device.port}")
    }

    private fun startAirMouse() {
        val sensor = gyroSensor ?: return
        lastGyroTimestamp = 0L
        sensorManager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopAirMouse() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(gyroListener)
        lastGyroTimestamp = 0L
    }

    private val gyroListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            if (!airMouseEnabled || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val now = event.timestamp
            val previous = lastGyroTimestamp
            lastGyroTimestamp = now
            if (previous == 0L) return
            val dt = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            // Ignore sensor noise and clamp each movement step to prevent cursor jumps.
            val rawX = event.values[2] * dt
            val rawY = event.values[0] * dt
            val dx = if (kotlin.math.abs(rawX) < 0.025f) 0f else (rawX * 620f * gyroSensitivity).coerceIn(-18f, 18f)
            val dy = if (kotlin.math.abs(rawY) < 0.025f) 0f else (rawY * 620f * gyroSensitivity).coerceIn(-18f, 18f)
            if (dx != 0f || dy != 0f) sendAirMouse(dx, dy)
        }
    }

    private fun sendAirMouse(dx: Float, dy: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAirSendAt < 55L) return
        lastAirSendAt = now
        io.execute {
            try {
                if (tcp?.isConnected != true || tcp?.isClosed != false) {
                    val ip = prefs.getString("ip", null) ?: throw IllegalStateException("ابحث عن التلفاز أولاً")
                    connect(Device("التلفاز", ip, discoverPort(ip)))
                }
                val packet = "AIRMOUSEEVNET\r\n$dx\r\n$dy\r\n2\r\n\r\n"
                output?.write(packet.toByteArray(Charsets.UTF_8))
                output?.flush()
            } catch (e: Exception) {
                closeConnection()
                setStatus("تعذر تحريك الماوس: ${e.message ?: "أعد الاتصال"}")
            }
        }
    }

    private fun sendKey(key: Int) {
        io.execute {
            try {
                if (tcp?.isConnected != true || tcp?.isClosed != false) {
                    val ip = prefs.getString("ip", null) ?: throw IllegalStateException("ابحث عن التلفاز أولاً")
                    connect(Device("التلفاز", ip, discoverPort(ip)))
                }
                output?.write("KEYEVENT\r\n$key\r\n\r\n".toByteArray(Charsets.UTF_8))
                output?.flush()
            } catch (e: Exception) {
                closeConnection()
                setStatus("انقطع الاتصال: ${e.message ?: "أعد البحث"}")
            }
        }
    }

    private fun closeConnection() {
        try { output?.close() } catch (_: Exception) { }
        try { tcp?.close() } catch (_: Exception) { }
        output = null
        tcp = null
    }

    override fun onDestroy() {
        closeConnection()
        io.shutdownNow()
        super.onDestroy()
    }
}

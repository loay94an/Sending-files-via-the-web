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
    private val gyroSensitivity = 0.22f
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
        val bg = 0xff071426.toInt()
        val panel = 0xff142944.toInt()
        val accent = 0xff16c9c3.toInt()
        val muted = 0xff91a9c7.toInt()

        fun rounded(color: Int, radius: Int = 18, stroke: Int = 0) =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != 0) setStroke(dp(1), stroke)
            }

        fun makeButton(label: String, primary: Boolean = false, height: Int = 44): Button =
            Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                minHeight = dp(height)
                minWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                setTextColor(if (primary) 0xff061521.toInt() else 0xfff2f7ff.toInt())
                background = rounded(if (primary) accent else 0xff1a3555.toInt(), 18,
                    if (primary) 0 else 0xff31577d.toInt())
                stateListAnimator = null
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(7), dp(12), dp(8))
            setBackgroundColor(bg)
        }

        // Header: Home and Back remain immediately accessible.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val home = makeButton("⌂\nالرئيسية", false, 48).apply {
            textSize = 11f
            setOnClickListener { sendKey(3) }
        }
        header.addView(home, LinearLayout.LayoutParams(dp(76), dp(48)))
        val brandBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val brand = TextView(this).apply {
            text = "وِصلة"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xfff5f9ff.toInt())
            gravity = Gravity.CENTER
        }
        brandBox.addView(brand, lp(-1, dp(27)))
        status = TextView(this).apply {
            text = "جارٍ البحث عن التلفاز..."
            textSize = 10f
            setTextColor(0xff5eead4.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        }
        brandBox.addView(status, lp(-1, dp(18)))
        header.addView(brandBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        val back = makeButton("↶\nرجوع", false, 48).apply {
            textSize = 11f
            setOnClickListener { sendKey(4) }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(76), dp(48)))
        root.addView(header, lp(-1, dp(50)))

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
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(0xff0c1b30.toInt(), 14, 0xff294967.toInt())
        }
        connection.addView(ipInput, LinearLayout.LayoutParams(0, dp(39), 1f).apply { marginEnd = dp(7) })
        connectButton = makeButton("اتصال", true, 39).apply {
            setOnClickListener { discoverAndConnect(ipInput.text.toString().trim().ifEmpty { null }) }
        }
        connection.addView(connectButton, LinearLayout.LayoutParams(dp(82), dp(39)))
        root.addView(connection, lp(-1, dp(41)))

        val touchTitle = TextView(this).apply {
            text = "لوحة اللمس  •  اسحب لتحريك المؤشر واضغط للاختيار"
            textSize = 11f
            setTextColor(0xffb9cde5.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(touchTitle, lp(-1, dp(24)))

        val touchPad = FrameLayout(this).apply {
            background = rounded(0xff102743.toInt(), 23, 0xff3185b8.toInt())
            contentDescription = "لوحة تحريك مؤشر التلفاز"
        }
        val touchHint = TextView(this).apply {
            text = "⌖\nمنطقة تحريك المؤشر"
            textSize = 14f
            setTextColor(0xff8fb4d8.toInt())
            gravity = Gravity.CENTER
        }
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
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(3)) moved = true
                    if (moved) {
                        sendAirMouse(dx * 0.55f, dy * 0.55f)
                        downX = event.x
                        downY = event.y
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) sendKey(23)
                    view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        root.addView(touchPad, LinearLayout.LayoutParams(-1, dp(142)).apply {
            topMargin = dp(2)
            bottomMargin = dp(10)
        })

        // Main control deck: volume on the left, D-pad in the middle,
        // and power/mute on the right. All side controls are large vertical targets.
        val deck = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        fun sidePanel(buttons: List<Pair<String, Int>>, width: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0xff10243d.toInt(), 24, 0xff294d70.toInt())
            setPadding(dp(5), dp(8), dp(5), dp(8))
            buttons.forEachIndexed { index, item ->
                val b = makeButton(item.first, false, 54).apply {
                    textSize = 15f
                    setOnClickListener { sendKey(item.second) }
                }
                addView(b, LinearLayout.LayoutParams(-1, dp(54)).apply {
                    if (index > 0) topMargin = dp(9)
                })
            }
        }

        deck.addView(sidePanel(listOf("🔊  + " to 24, "🔉  −" to 25), 0),
            LinearLayout.LayoutParams(dp(76), dp(132)).apply { marginEnd = dp(7) })

        val navArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = rounded(0xff10223a.toInt(), 30, 0xff247b9c.toInt())
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        fun navButton(symbol: String, key: Int?, size: Int, primary: Boolean = false): Button =
            Button(this).apply {
                text = symbol
                textSize = if (primary) 17f else 19f
                isAllCaps = false
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setTextColor(if (primary) 0xff061521.toInt() else 0xfff2f7ff.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (primary) accent else 0xff1c3859.toInt())
                    if (!primary) setStroke(dp(1), 0xff41678b.toInt())
                }
                stateListAnimator = null
                if (key != null) setOnClickListener { sendKey(key) }
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            }
        fun navRow(left: Button, center: Button, right: Button) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                addView(left); addView(center); addView(right)
            }
        navArea.addView(navRow(navButton(" ", null, 37), navButton("▲", 19, 42), navButton(" ", null, 37)))
        navArea.addView(navRow(navButton("◀", 21, 42), navButton("OK", 23, 54, true), navButton("▶", 22, 42)))
        navArea.addView(navRow(navButton(" ", null, 37), navButton("▼", 20, 42), navButton(" ", null, 37)))
        deck.addView(navArea, LinearLayout.LayoutParams(0, dp(132), 1f).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })

        deck.addView(sidePanel(listOf("⏻" to 26, "🔇" to 164), 0),
            LinearLayout.LayoutParams(dp(76), dp(132)).apply { marginStart = dp(7) })
        root.addView(deck, lp(-1, dp(140)))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val voice = makeButton("🎙  صوت", false, 49).apply {
            textSize = 14f
            setOnClickListener {
                try {
                    val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar")
                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "قل أمراً للتحكم بالتلفاز")
                    }
                    startActivityForResult(intent, 701)
                } catch (_: Exception) {
                    setStatus("ميزة التعرف الصوتي غير متاحة على هذا الهاتف")
                }
            }
        }
        bottom.addView(voice, LinearLayout.LayoutParams(0, dp(49), 1f).apply { marginEnd = dp(5) })
        val airToggle = makeButton("⌁  ماوس هوائي", true, 49).apply {
            isEnabled = gyroSensor != null
            if (gyroSensor == null) text = "مستشعر الحركة غير متاح"
            setOnClickListener {
                airMouseEnabled = !airMouseEnabled
                text = if (airMouseEnabled) "إيقاف الماوس الهوائي" else "⌁  ماوس هوائي"
                if (airMouseEnabled) startAirMouse() else stopAirMouse()
                setStatus(if (airMouseEnabled) "الماوس الهوائي مفعّل" else "التحكم بلوحة اللمس")
            }
        }
        bottom.addView(airToggle, LinearLayout.LayoutParams(0, dp(49), 1f).apply { marginStart = dp(5) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(7) })

        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701 || resultCode != RESULT_OK) return
        val spoken = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim()?.lowercase(java.util.Locale("ar")) ?: return
        when {
            spoken.contains("الرئيسية") || spoken.contains("هوم") -> sendKey(3)
            spoken.contains("رجوع") || spoken.contains("عودة") -> sendKey(4)
            spoken.contains("يمين") -> sendKey(22)
            spoken.contains("يسار") -> sendKey(21)
            spoken.contains("فوق") || spoken.contains("أعلى") -> sendKey(19)
            spoken.contains("تحت") || spoken.contains("أسفل") -> sendKey(20)
            spoken.contains("موافق") || spoken.contains("اختيار") -> sendKey(23)
            spoken.contains("ارفع الصوت") || spoken.contains("الصوت أعلى") -> sendKey(24)
            spoken.contains("اخفض الصوت") || spoken.contains("الصوت أقل") -> sendKey(25)
            spoken.contains("كتم") -> sendKey(164)
            spoken.contains("طاقة") || spoken.contains("إيقاف") -> sendKey(26)
            else -> setStatus("لم أفهم الأمر: $spoken")
        }
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
            val dx = if (kotlin.math.abs(rawX) < 0.0012f) 0f else (rawX * 180f * gyroSensitivity).coerceIn(-4f, 4f)
            val dy = if (kotlin.math.abs(rawY) < 0.0012f) 0f else (rawY * 180f * gyroSensitivity).coerceIn(-4f, 4f)
            if (dx != 0f || dy != 0f) sendAirMouse(dx, dy)
        }
    }

    private fun sendAirMouse(dx: Float, dy: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAirSendAt < 35L) return
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

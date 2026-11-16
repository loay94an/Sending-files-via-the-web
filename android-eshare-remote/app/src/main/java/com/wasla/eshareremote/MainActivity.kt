package com.wasla.eshareremote

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
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
        buildUi()
        val saved = prefs.getString("ip", "") ?: ""
        ipInput.setText(saved)
        discoverAndConnect()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xff0b1220.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(this).apply {
            text = "وصلة"
            textSize = 21f
            setTextColor(0xfff1f5f9.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
        status = TextView(this).apply {
            text = "جارٍ البحث عن التلفاز..."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xff5eead4.toInt())
            maxLines = 2
        }
        header.addView(status, LinearLayout.LayoutParams(0, dp(36), 2f))
        root.addView(header, lp(-1, dp(36)))

        val connection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        ipInput = EditText(this).apply {
            hint = "IP التلفاز"
            textSize = 13f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setTextColor(0xfff8fafc.toInt())
            setHintTextColor(0xff94a3b8.toInt())
            setPadding(dp(8), 0, dp(8), 0)
            backgroundTintList = ColorStateList.valueOf(0xff475569.toInt())
        }
        connection.addView(ipInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        connectButton = Button(this).apply {
            text = "اتصال"
            textSize = 12f
            isAllCaps = false
            minHeight = dp(40)
            backgroundTintList = ColorStateList.valueOf(0xff0f766e.toInt())
            setTextColor(0xffffffff.toInt())
            setOnClickListener { discoverAndConnect(ipInput.text.toString().trim().ifEmpty { null }) }
        }
        connection.addView(connectButton, LinearLayout.LayoutParams(dp(92), dp(42)))
        root.addView(connection, lp(-1, dp(42)))

        addSectionLabel(root, "الاتجاهات")
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        pad.addView(buttonRow(" ", "▲", " ", null, 19, null))
        pad.addView(buttonRow("◀", "●", "▶", 21, 23, 22))
        pad.addView(buttonRow(" ", "▼", " ", null, 20, null))
        root.addView(pad, lp(-1, dp(132)))

        addSectionLabel(root, "لوحة اللمس")
        val touchPad = FrameLayout(this).apply {
            setBackgroundColor(0xff1e293b.toInt())
            contentDescription = "لوحة لمس التلفاز؛ انقر على موضع الشاشة لإرسال اللمسة"
        }
        val touchHint = TextView(this).apply {
            text = "المس هنا للتحكم بمؤشر التلفاز"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xffcbd5e1.toInt())
        }
        touchPad.addView(touchHint, FrameLayout.LayoutParams(-1, -1))
        // The TV's EShare build accepts KEYEVENT reliably, but its touch-event
        // packet format is not confirmed. Use the verified D-pad protocol for
        // swipe navigation instead of sending the unsupported TOUCHLOGNPRESS.
        touchPad.setOnTouchListener { view, event ->
            val threshold = dp(18).toFloat()
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.setTag(intArrayOf(event.x.toInt(), event.y.toInt(), 0, 0))
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val state = view.getTag() as? IntArray
                    if (state != null) {
                        var dx = event.x.toInt() - state[0]
                        var dy = event.y.toInt() - state[1]
                        var moved = false
                        while (kotlin.math.abs(dx) >= threshold) {
                            sendKey(if (dx > 0) 22 else 21) // right / left
                            dx += if (dx > 0) -threshold.toInt() else threshold.toInt()
                            moved = true
                        }
                        while (kotlin.math.abs(dy) >= threshold) {
                            sendKey(if (dy > 0) 20 else 19) // down / up
                            dy += if (dy > 0) -threshold.toInt() else threshold.toInt()
                            moved = true
                        }
                        state[0] = event.x.toInt()
                        state[1] = event.y.toInt()
                        if (moved) state[2] = 1
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val state = view.getTag() as? IntArray
                    if (state == null || state[2] == 0) sendKey(23) // tap = OK/select
                    view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        root.addView(touchPad, LinearLayout.LayoutParams(-1, dp(112)).apply { bottomMargin = dp(4) })

        addSectionLabel(root, "التحكم السريع")
        val actions = compactRow()
        actions.addView(actionButton("رجوع", 4), compactWeighted())
        actions.addView(actionButton("الرئيسية", 3), compactWeighted())
        actions.addView(actionButton("القائمة", 82), compactWeighted())
        root.addView(actions, lp(-1, dp(42)))

        val volume = compactRow()
        volume.addView(actionButton("− صوت", 25), compactWeighted())
        volume.addView(actionButton("كتم", 91), compactWeighted())
        volume.addView(actionButton("+ صوت", 24), compactWeighted())
        root.addView(volume, lp(-1, dp(42)))

        val media = compactRow()
        media.addView(actionButton("السابق", 88), compactWeighted())
        media.addView(actionButton("▶ / ❚❚", 85, true), compactWeighted())
        media.addView(actionButton("التالي", 87), compactWeighted())
        root.addView(media, lp(-1, dp(42)))

        val system = compactRow()
        system.addView(actionButton("قناة −", 167), compactWeighted())
        system.addView(actionButton("قناة +", 166), compactWeighted())
        system.addView(actionButton("⏻ طاقة", 26), compactWeighted())
        root.addView(system, lp(-1, dp(42)))

        val refresh = Button(this).apply {
            text = "إعادة البحث عن التلفاز"
            textSize = 11f
            isAllCaps = false
            minHeight = dp(36)
            backgroundTintList = ColorStateList.valueOf(0xff263449.toInt())
            setTextColor(0xffcbd5e1.toInt())
            setOnClickListener { discoverAndConnect() }
        }
        root.addView(refresh, lp(-1, dp(36)))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
            setBackgroundColor(0xff0b1220.toInt())
        })
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
            minHeight = dp(if (primary) 48 else 40)
            setTextColor(0xfff8fafc.toInt())
            backgroundTintList = ColorStateList.valueOf(if (primary) 0xff0f766e.toInt() else 0xff263449.toInt())
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
        // Keep Android Wi-Fi broadcast/multicast discovery packets available.
        try {
            discoveryLock?.let { if (it.isHeld) it.release() }
            discoveryLock = wifi.createMulticastLock("wasla-eshare-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }

        // Reuse the last successful device first; no address entry is required.
        val saved = prefs.getString("ip", null)
        if (!saved.isNullOrBlank() && isControlPortOpen(saved, 8121)) {
            return Device("التلفاز المحفوظ", saved, 8121)
        }

        val broadcast = if (dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            val b = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b).array())
        } else InetAddress.getByName("255.255.255.255")

        try {
            DatagramSocket(48689).use { udp ->
                udp.broadcast = true
                udp.soTimeout = 700
                val bytes = packet()
                udp.send(DatagramPacket(bytes, bytes.size, broadcast, 48689))
                val buffer = ByteArray(1024)
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
            }
        } catch (_: Exception) { }

        // Some firmware does not answer UDP discovery. Locate its known working
        // TCP control service on the local Wi-Fi subnet instead.
        setStatus("لم يرد اكتشاف UDP؛ جارٍ البحث عن التلفاز داخل شبكة Wi-Fi...")
        if (dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            val local = dhcp.ipAddress
            val mask = dhcp.netmask
            val network = local and mask
            val broadcastIp = network or mask.inv()
            val found = java.util.concurrent.atomic.AtomicReference<Device?>(null)
            val pool = Executors.newFixedThreadPool(32)
            val tasks = (network + 1 until broadcastIp).filter { it != local }.map { host ->
                pool.submit {
                    if (found.get() == null) {
                        val ip = listOf(host and 255, (host ushr 8) and 255, (host ushr 16) and 255, (host ushr 24) and 255).joinToString(".")
                        if (isControlPortOpen(ip, 8121)) {
                            found.compareAndSet(null, Device("تلفاز EShare", ip, 8121))
                        }
                    }
                }
            }
            val deadline = System.currentTimeMillis() + 6500
            while (System.currentTimeMillis() < deadline && found.get() == null && tasks.any { !it.isDone }) {
                Thread.sleep(80)
            }
            pool.shutdownNow()
            found.get()?.let { device ->
                prefs.edit().putString("ip", device.ip).apply()
                runOnUiThread { ipInput.setText(device.ip) }
                return device
            }
        }
        throw IllegalStateException("لم يتم العثور على تلفاز EShare. تأكد أن الهاتف والتلفاز على شبكة Wi-Fi نفسها، أو أدخل IP التلفاز يدوياً ثم اضغط اتصال. منفذ التحكم المتوقع 8121.")
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

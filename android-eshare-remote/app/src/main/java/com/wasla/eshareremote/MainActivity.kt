package com.wasla.eshareremote

import android.app.Activity
import android.os.Bundle
import android.content.Context
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
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private val prefs by lazy { getSharedPreferences("remote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff102a43.toInt()
        window.navigationBarColor = 0xff102a43.toInt()
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
            setPadding(20, 18, 20, 18)
            setBackgroundColor(0xfff3f6fa.toInt())
        }
        val title = TextView(this).apply {
            text = "وصلة ريموت"
            textSize = 26f
            setTextColor(0xff102a43.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, lp(-1, -2))
        status = TextView(this).apply {
            text = "جارٍ البحث عن التلفاز..."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xff486581.toInt())
            setPadding(4, 12, 4, 12)
        }
        root.addView(status, lp(-1, -2))
        ipInput = EditText(this).apply {
            hint = "عنوان التلفاز (اختياري)"
            textSize = 15f
            setSingleLine(true)
            inputType =  android.text.InputType.TYPE_CLASS_TEXT
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(ipInput, lp(-1, -2))
        connectButton = Button(this).apply {
            text = "اكتشاف / اتصال"
            setOnClickListener { discoverAndConnect(ipInput.text.toString().trim().ifEmpty { null }) }
        }
        root.addView(connectButton, lp(-1, -2))

        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        pad.addView(buttonRow(" ", "▲", " ", null, 19, null))
        pad.addView(buttonRow("◀", "●", "▶", 21, 23, 22))
        pad.addView(buttonRow(" ", "▼", " ", null, 20, null))
        root.addView(pad, lp(-1, -2))

        val touchTitle = TextView(this).apply {
            text = "التحكم باللمس والماوس"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xff102a43.toInt())
            setPadding(0, dp(14), 0, dp(6))
        }
        root.addView(touchTitle, lp(-1, -2))
        val touchPad = FrameLayout(this).apply {
            setBackgroundColor(0xffdce8f5.toInt())
            contentDescription = "لوحة لمس التلفاز؛ انقر على موضع الشاشة لإرسال اللمسة"
        }
        val touchHint = TextView(this).apply {
            text = "المس هنا لتحديد موضع النقر على شاشة التلفاز"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xff24415f.toInt())
        }
        touchPad.addView(touchHint, FrameLayout.LayoutParams(-1, -1))
        touchPad.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val x = (event.x / view.width.coerceAtLeast(1) * 1280f).toInt().coerceIn(0, 1279)
                val y = (event.y / view.height.coerceAtLeast(1) * 720f).toInt().coerceIn(0, 719)
                sendTouchTap(x, y)
            }
            true
        }
        root.addView(touchPad, LinearLayout.LayoutParams(-1, dp(190)).apply { bottomMargin = dp(8) })

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("رجوع", 4), weighted())
        actions.addView(actionButton("الرئيسية", 3), weighted())
        actions.addView(actionButton("القائمة", 82), weighted())
        root.addView(actions, lp(-1, -2))

        val volume = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        volume.addView(actionButton("الصوت −", 25), weighted())
        volume.addView(actionButton("كتم", 91), weighted())
        volume.addView(actionButton("الصوت +", 24), weighted())
        root.addView(volume, lp(-1, -2))
        val refresh = Button(this).apply {
            text = "إعادة البحث"
            setOnClickListener { discoverAndConnect() }
        }
        root.addView(refresh, lp(-1, -2))
        setContentView(ScrollView(this).apply { addView(root) })
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

    private fun spacer(): View = View(this).apply { minimumHeight = dp(58) }
    private fun actionButton(label: String, key: Int, primary: Boolean = false): Button =
        Button(this).apply {
            text = label
            textSize = if (primary) 22f else 14f
            isAllCaps = false
            minHeight = dp(if (primary) 68 else 54)
            setOnClickListener { sendKey(key) }
        }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply { bottomMargin = dp(7) }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(58), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
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
        throw IllegalStateException("لم يتم العثور على تلفاز EShare على شبكة Wi-Fi نفسها")
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

    private fun sendTouchTap(x: Int, y: Int) {
        io.execute {
            try {
                if (tcp?.isConnected != true || tcp?.isClosed != false) {
                    val ip = prefs.getString("ip", null) ?: throw IllegalStateException("ابحث عن التلفاز أولاً")
                    connect(Device("التلفاز", ip, discoverPort(ip)))
                }
                val message = "TOUCHLOGNPRESS\r\\n[={$x,$y}]\r\\n\r\\n"
                output?.write(message.toByteArray(Charsets.UTF_8))
                output?.flush()
                setStatus("تم إرسال اللمسة إلى الشاشة: $x, $y")
            } catch (e: Exception) {
                closeConnection()
                setStatus("تعذر إرسال اللمسة: " + (e.message ?: "أعد الاتصال"))
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

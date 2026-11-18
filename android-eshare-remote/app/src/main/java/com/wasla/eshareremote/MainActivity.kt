package com.wasla.eshareremote

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Typeface
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.io.OutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val connectionMonitor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val networkRecoveryHandler = Handler(Looper.getMainLooper())
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastDefaultNetwork: Network? = null
    private val availableLocalNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    private val networkRecoveryTask = Runnable {
        // Network callbacks are informational only while the current EShare
        // socket is alive. Never replace a working session because Android
        // reports a route/default-network transition.
        if (hasActiveEshareSession()) return@Runnable
        if (availableLocalNetworks.isNotEmpty()) {
            nextReconnectAt = 0L
            scheduleAutomaticReconnect("عادت الشبكة المحلية — استعادة الشاشة المحددة", immediate = true)
        } else {
            setStatus("الشبكة غير متاحة — سيُستعاد الاتصال عند عودتها")
        }
    }
    private val reconnecting = AtomicBoolean(false)
    private val connectionLock = Any()
    private val gestureHandler = Handler(Looper.getMainLooper())
    // Keep pointer traffic off the discovery/key-command executor to avoid input stalls.
    private val airMouseIo = Executors.newSingleThreadExecutor()
    private val socketReader = Executors.newSingleThreadExecutor()
    private val gyroPositionLock = Any()
    private val socketWriteLock = Any()
    private val socketReadLock = Any()
    // EShare framebuffer dimensions are queried from GETFBINFO after connecting.
    private var framebufferWidth = 1280f
    private var framebufferHeight = 720f
    private var gyroCursorX = 640f
    private var gyroCursorY = 360f
    private var touchStartRemoteX = 640f
    private var touchStartRemoteY = 360f
    private var touchStartAt = 0L
    private var pendingDownloadId: String? = null
    private var pendingDownloadName: String = "wasla-download.bin"
    private val tvFolderStack = mutableListOf<String>()
    @Volatile private var peerJsConnected = false
    @Volatile private var peerJsPageLoaded = false
    private var peerJsWebView: WebView? = null
    @Volatile private var peerJsDeviceId = ""
    @Volatile private var peerJsPairCode = ""
    private val peerJsMessageId = AtomicLong(0L)
    private val peerActionTimeouts = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    private var pendingGyroPosition: Pair<Float, Float>? = null
    private var gyroPositionWriteQueued = false
    private var lastTouchMoveAt = 0L
    private var pendingTouchDx = 0f
    private var pendingTouchDy = 0f
    @Volatile private var tcp: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var localHttpServer: ServerSocket? = null
    @Volatile private var localHttpThread: Thread? = null
    private var discoveryLock: WifiManager.MulticastLock? = null
    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null
    private var airMouseEnabled = false
    private var lastGyroTimestamp = 0L
    private var smoothedGyroDx = 0f
    private var smoothedGyroDy = 0f
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private var connectionIpLabel: TextView? = null
    private lateinit var textInput: EditText
    private var suppressTextWatcher = false
    private val prefs by lazy { getSharedPreferences("remote", Context.MODE_PRIVATE) }
    @Volatile private var waslaTvSocket: Socket? = null
    @Volatile private var waslaTvOutput: OutputStream? = null
    private val waslaTvLock = Any()
    private val waslaLinkExecutor = Executors.newSingleThreadExecutor()
    private val waslaLinkMonitor = Executors.newSingleThreadScheduledExecutor()
    private val waslaConnecting = AtomicBoolean(false)
    private val waslaMonitorStarted = AtomicBoolean(false)
    @Volatile private var lastWaslaPongAt = 0L
    @Volatile private var lastWaslaPingAt = 0L
    @Volatile private var lastEshareKeepAliveAt = 0L
    @Volatile private var lastFullDiscoveryAt = 0L
    @Volatile private var nextReconnectAt = 0L
    @Volatile private var reconnectAttempts = 0

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
        initPeerJsWebView()
        val saved = prefs.getString("ip", "") ?: ""
        ipInput.setText(saved)
        registerNetworkCallback()
        startConnectionMonitor()
        if (saved.isNotBlank()) scheduleAutomaticReconnect("استعادة آخر شاشة محفوظة", immediate = true)
        else discoverAndConnect()
        val savedWaslaIp = prefs.getString("wasla_tv_ip", "") ?: ""
        val savedWaslaCode = prefs.getString("wasla_tv_code", "") ?: ""
        val savedWaslaId = prefs.getString("wasla_tv_id", "") ?: ""
        startWaslaLinkMonitor()
        if (savedWaslaIp.isNotBlank() && savedWaslaCode.isNotBlank() && savedWaslaId.isNotBlank()) {
            startPeerJsTransport(savedWaslaId, savedWaslaCode)
            connectWaslaReceiver(savedWaslaIp, savedWaslaCode, savedWaslaId)
        }
    }

    private fun buildUi() {
        val bg = 0xff050d1b.toInt()
        val panel = 0xff101f38.toInt()
        val accent = 0xff16c9f4.toInt()
        val muted = 0xff9db5d4.toInt()
        val white = 0xfff4f8ff.toInt()
        val violet = 0xff6255e8.toInt()

        fun surface(color: Int, radius: Int = 22, stroke: Int = 0xff294d78.toInt()) =
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(color, 0xff0b1730.toInt())
            ).apply {
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), stroke)
            }

        fun makeButton(label: String, height: Int = 54, primary: Boolean = false): Button =
            Button(this).apply {
                text = label
                textSize = 14f
                isAllCaps = false
                minHeight = dp(height)
                minWidth = 0
                setPadding(dp(5), dp(3), dp(5), dp(3))
                setTextColor(if (primary) 0xff031426.toInt() else white)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed),
                        surface(if (primary) 0xff087fae.toInt() else 0xff17618d.toInt(), 20, 0xff62eaff.toInt()))
                    addState(intArrayOf(),
                        if (primary) surface(0xff16bce9.toInt(), 18, 0xff59eaff.toInt())
                        else surface(panel, 20, 0xff315987.toInt()))
                }
                stateListAnimator = null
                elevation = dp(4).toFloat()
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(13), dp(8), dp(13), dp(10))
            setBackgroundColor(bg)
        }

        // Premium header: brand centered, mute and power as real controls.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = surface(0xff101d35.toInt(), 25, 0xff263f68.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val mute = makeButton("◖×\nكتم", 58).apply {
            textSize = 13f
            setOnClickListener { sendKey(164) }
        }
        header.addView(mute, LinearLayout.LayoutParams(dp(72), dp(58)))
        val brandBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val brand = TextView(this).apply {
            text = "⌁ وصلة"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.CENTER
        }
        brandBox.addView(brand, lp(-1, dp(34)))
        status = TextView(this).apply {
            text = "جارٍ البحث عن التلفاز..."
            textSize = 11f
            setTextColor(0xff19e6a2.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        }
        brandBox.addView(status, lp(-1, dp(20)))
        header.addView(brandBox, LinearLayout.LayoutParams(0, dp(56), 1f))
        val power = makeButton("⏻\nطاقة", 58).apply {
            textSize = 14f
            setTextColor(0xffff657c.toInt())
            setOnClickListener { sendKey(26) }
        }
        header.addView(power, LinearLayout.LayoutParams(dp(72), dp(58)))
        val settingsButton = makeButton("⚙", 48).apply {
            textSize = 20f
            contentDescription = "إعدادات وصلة"
            setOnClickListener { showSettingsDialog() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(58)).apply {
            marginStart = dp(4)
        })
        root.addView(header, lp(-1, dp(72)))

        // Separate direct discovery from QR pairing for a clearer two-action connection UI.
        ipInput = EditText(this) // retained internally for discovered-IP compatibility; not shown in the main UI
        val connectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = surface(0xff0c192d.toInt(), 16, 0xff263f61.toInt())
            setPadding(dp(7), dp(3), dp(7), dp(3))
        }
        connectButton = makeButton("اتصال", 40, true).apply {
            textSize = 13f
            // Manual Connect always starts a fresh scan; automatic recovery stays pinned.
            setOnClickListener { discoverWaslaThenEShare(interactive = true) }
            setOnLongClickListener {
                discoverWaslaThenEShare(interactive = true)
                true
            }
        }
        connectionActions.addView(connectButton, LinearLayout.LayoutParams(dp(104), dp(40)).apply {
            marginEnd = dp(7)
        })
        val qrButton = makeButton("⌗", 38, false).apply {
            textSize = 18f
            contentDescription = "مسح رمز QR للاتصال"
            setOnClickListener { startWaslaQrScanner() }
        }
        connectionActions.addView(qrButton, LinearLayout.LayoutParams(dp(40), dp(38)).apply {
            marginEnd = dp(9)
        })
        connectionIpLabel = TextView(this).apply {
            text = "⌁  غير متصل"
            textSize = 12f
            setTextColor(0xff9eb5d4.toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        connectionIpLabel?.setOnClickListener { showIpEditor() }
        connectionIpLabel?.setOnLongClickListener {
            showIpEditor()
            true
        }
        connectionActions.addView(connectionIpLabel, LinearLayout.LayoutParams(0, dp(38), 1f))
        root.addView(connectionActions, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(7); bottomMargin = dp(3)
        })

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val castButton = makeButton("▣  مشاهدة الشاشة", 48, true).apply {
            textSize = 13f
            setOnClickListener { startActivity(Intent(this@MainActivity, ScreenViewerActivity::class.java)) }
        }
        shortcuts.addView(castButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        val filesButton = makeButton("⇧  نقل الملفات", 48).apply {
            textSize = 13f
            setOnClickListener { pickFileForTv() }
        }
        shortcuts.addView(filesButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(shortcuts, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(7); bottomMargin = dp(3) })

        // Phone keyboard input sits above the sharing and file-transfer shortcuts.
        val textEntry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = surface(0xff0c192d.toInt(), 17, 0xff263f61.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        textInput = EditText(this).apply {
            hint = "اكتب نصاً لإرساله إلى التلفاز"
            textSize = 14f
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(9), 0, dp(9), 0)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        textEntry.addView(textInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        val sendTextButton = makeButton("إرسال", 42, true).apply {
            textSize = 13f
            setOnClickListener {
                val value = textInput.text.toString()
                if (value.isNotBlank()) {
                    sendText(value)
                    suppressTextWatcher = true
                    textInput.text.clear()
                    suppressTextWatcher = false
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(textInput.windowToken, 0)
                }
            }
        }
        textEntry.addView(sendTextButton, LinearLayout.LayoutParams(dp(76), dp(42)))
        // Keep typing local on the phone. Send a complete UTF-8 composing + commit
        // sequence only when Send is pressed, avoiding stale composing indexes and
        // asynchronous per-keystroke packets that could arrive out of order.
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendTextButton.performClick()
                true
            } else false
        }
        root.addView(textEntry, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(7); bottomMargin = dp(5)
        })

        val touchPad = FrameLayout(this).apply {
            background = surface(0xff102c50.toInt(), 25, 0xff168fd2.toInt())
            elevation = dp(5).toFloat()
            contentDescription = "لوحة لمس لتحريك مؤشر التلفاز"
        }
        val touchHint = TextView(this).apply {
            text = "⌖\nلوحة اللمس\nاسحب لتحريك المؤشر واضغط للاختيار"
            textSize = 15f
            setTextColor(0xffd5e9ff.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        touchPad.addView(touchHint, FrameLayout.LayoutParams(-1, -1))
        var downX = 0f
        var downY = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f
        var moved = false
        var longPressTriggered = false
        val longPressStart = Runnable {
            if (!moved && !longPressTriggered) {
                longPressTriggered = true
                sendPointerHold(true)
            }
        }
        touchPad.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    touchStartRemoteX = gyroCursorX
                    touchStartRemoteY = gyroCursorY
                    touchStartAt = System.currentTimeMillis()
                    lastTouchX = event.x; lastTouchY = event.y
                    moved = false
                    longPressTriggered = false
                    gestureHandler.removeCallbacks(longPressStart)
                    gestureHandler.postDelayed(longPressStart, 620L)
                    // Do not send synthetic mouse-down/up packets here: on some
                    // EShare builds those reset the relative pointer to screen center.
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.x - downX
                    val totalDy = event.y - downY
                    // Ignore tiny finger jitter so a tap remains a click.
                    val moveThreshold = if (airMouseEnabled) dp(18) else dp(6)
                    if (kotlin.math.abs(totalDx) + kotlin.math.abs(totalDy) > moveThreshold) {
                        moved = true
                        gestureHandler.removeCallbacks(longPressStart)
                    }
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    if (!airMouseEnabled && moved && (dx != 0f || dy != 0f)) {
                        sendTouchMouseEvent(dx, dy, 2, view.width, view.height)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    gestureHandler.removeCallbacks(longPressStart)
                    if (longPressTriggered) {
                        sendPointerHold(false)
                        view.performClick()
                        return@setOnTouchListener true
                    }
                    if (!airMouseEnabled) sendTouchMouseEvent(0f, 0f, 1, view.width, view.height)
                    val totalDx = event.x - downX
                    val totalDy = event.y - downY
                    if (airMouseEnabled && moved) {
                        // In air-mouse mode the pad becomes a directional navigator.
                        if (kotlin.math.abs(totalDx) >= kotlin.math.abs(totalDy)) {
                            sendKey(if (totalDx >= 0f) 22 else 21)
                        } else {
                            sendKey(if (totalDy >= 0f) 20 else 19)
                        }
                    } else if (airMouseEnabled) {
                        // Air-mouse tap clicks the current gyro pointer position,
                        // matching the corrected normal touchpad click behavior.
                        sendPointerClick()
                    } else if (moved && waslaTvSocket != null) {
                        sendWaslaSwipe(
                            touchStartRemoteX, touchStartRemoteY, gyroCursorX, gyroCursorY,
                            (System.currentTimeMillis() - touchStartAt).coerceIn(80L, 2000L)
                        )
                    } else if (!moved) {
                        // Click the current absolute pointer position; do not send
                        // KEYEVENT OK, which selects focus rather than the cursor target.
                        sendPointerClick()
                    }
                    view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    gestureHandler.removeCallbacks(longPressStart)
                    if (longPressTriggered) sendPointerHold(false)
                    true
                }
                else -> true
            }
        }
        root.addView(touchPad, LinearLayout.LayoutParams(-1, dp(168)).apply {
            bottomMargin = dp(13)
        })

        // Main deck mirrors the approved realistic mockup.
        val deck = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        fun sideButton(label: String, key: Int, height: Int = 78, tint: Int = white) =
            makeButton(label, height).apply {
                textSize = 15f
                setTextColor(tint)
                setOnClickListener { sendKey(key) }
            }

        val leftStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        leftStack.addView(sideButton("⌂\nهوم", 3), LinearLayout.LayoutParams(-1, dp(78)))
        leftStack.addView(sideButton("↶\nرجوع", 4), LinearLayout.LayoutParams(-1, dp(78)).apply {
            topMargin = dp(9)
        })
        deck.addView(leftStack, LinearLayout.LayoutParams(dp(78), dp(165)).apply {
            marginEnd = dp(7)
        })

        // Four directional sectors form one continuous ring around the unchanged OK button.
        val navArea = object : View(this) {
            private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.FILL
            }
            private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                color = 0xff477ca8.toInt()
            }
            private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xff8cecff.toInt()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = dp(25).toFloat()
            }
            private val centerLabel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xffffffff.toInt()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = dp(15).toFloat()
            }
            private var pressedSector = -1
            private var okLongPressTriggered = false
            private val okLongPress = Runnable {
                if (pressedSector == 4 && !okLongPressTriggered) {
                    okLongPressTriggered = true
                    // Android TV context/options key. A normal short press remains DPAD_CENTER.
                    sendKey(82)
                    setStatus("تم طلب قائمة الخيارات")
                    invalidate()
                }
            }
            private val keys = intArrayOf(19, 22, 20, 21)
            private val symbols = arrayOf("▲", "▶", "▼", "◀")

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val outer = (kotlin.math.min(width, height) / 2f) - dp(5).toFloat()
                val inner = outer * 0.43f
                val bounds = android.graphics.RectF(cx - outer, cy - outer, cx + outer, cy + outer)
                for (i in 0..3) {
                    fill.color = if (pressedSector == i) 0xff16bce9.toInt() else 0xff173657.toInt()
                    canvas.drawArc(bounds, -135f + i * 90f, 90f, true, fill)
                }
                canvas.drawCircle(cx, cy, outer, line)
                canvas.drawLine(cx, cy - outer, cx, cy - inner, line)
                canvas.drawLine(cx, cy + inner, cx, cy + outer, line)
                canvas.drawLine(cx - outer, cy, cx - inner, cy, line)
                canvas.drawLine(cx + inner, cy, cx + outer, cy, line)
                fill.color = if (pressedSector == 4) 0xff12a8df.toInt() else 0xff087fc7.toInt()
                canvas.drawCircle(cx, cy, inner, fill)
                line.color = 0xff54eaff.toInt()
                canvas.drawCircle(cx, cy, inner, line)
                line.color = 0xff477ca8.toInt()
                val radius = (outer + inner) / 2f
                for (i in 0..3) {
                    val angle = Math.toRadians((-90.0 + i * 90.0))
                    val x = cx + kotlin.math.cos(angle).toFloat() * radius
                    val y = cy + kotlin.math.sin(angle).toFloat() * radius
                    canvas.drawText(symbols[i], x, y - (label.ascent() + label.descent()) / 2f, label)
                }
                canvas.drawText("OK", cx, cy - (centerLabel.ascent() + centerLabel.descent()) / 2f, centerLabel)
            }

            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                val cx = width / 2f
                val cy = height / 2f
                val dx = event.x - cx
                val dy = event.y - cy
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                val outer = (kotlin.math.min(width, height) / 2f) - dp(5).toFloat()
                fun hitSector(): Int {
                    if (distance <= outer * 0.43f) return 4
                    if (distance > outer) return -1
                    val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
                    return when {
                        angle >= -135 && angle < -45 -> 0
                        angle >= -45 && angle < 45 -> 1
                        angle >= 45 && angle < 135 -> 2
                        else -> 3
                    }
                }
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        gestureHandler.removeCallbacks(okLongPress)
                        pressedSector = hitSector()
                        okLongPressTriggered = false
                        if (pressedSector == 4) gestureHandler.postDelayed(okLongPress, 600L)
                        invalidate()
                        return pressedSector >= 0
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val next = hitSector()
                        if (next != pressedSector) {
                            gestureHandler.removeCallbacks(okLongPress)
                            pressedSector = next
                            invalidate()
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        gestureHandler.removeCallbacks(okLongPress)
                        val selected = hitSector()
                        val pressed = pressedSector
                        val wasLongPress = okLongPressTriggered
                        pressedSector = -1
                        okLongPressTriggered = false
                        invalidate()
                        if (!wasLongPress && selected >= 0 && selected == pressed) {
                            sendKey(if (selected == 4) 23 else keys[selected])
                        }
                        performClick()
                        return true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        gestureHandler.removeCallbacks(okLongPress)
                        pressedSector = -1
                        okLongPressTriggered = false
                        invalidate()
                        return true
                    }
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            contentDescription = "أزرار الاتجاهات حول زر OK؛ اضغط مطولاً على OK لفتح الخيارات"
            isClickable = true
            elevation = dp(7).toFloat()
        }


        // Restore the missing center directional pad in the deck.
        deck.addView(navArea, LinearLayout.LayoutParams(0, dp(165), 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })

        val volumeStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        volumeStack.addView(sideButton("◖ +\nالصوت", 24), LinearLayout.LayoutParams(-1, dp(78)))
        volumeStack.addView(sideButton("◖ −\nالصوت", 25), LinearLayout.LayoutParams(-1, dp(78)).apply {
            topMargin = dp(9)
        })
        deck.addView(volumeStack, LinearLayout.LayoutParams(dp(78), dp(165)).apply {
            marginStart = dp(7)
        })
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val appShortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        for (slot in 1..3) {
            val savedLabel = prefs.getString("tv_app_shortcut_${slot}_label", null)
            val shortcutButton = makeButton(savedLabel ?: "＋", 44).apply {
                textSize = 10f
                maxLines = 1
                setPadding(dp(2), dp(2), dp(2), dp(2))
                contentDescription = savedLabel ?: "اضغط مطولاً لاختيار تطبيق من التلفاز"
                setOnClickListener { launchTvAppShortcut(slot) }
                setOnLongClickListener {
                    chooseTvAppShortcut(slot, this)
                    true
                }
            }
            appShortcuts.addView(shortcutButton, LinearLayout.LayoutParams(dp(48), dp(44)).apply {
                if (slot > 1) setMargins(dp(3), 0, 0, 0)
            })
        }
        val airToggle = makeButton("⌁ الهوائي", 44, true).apply {
            textSize = 14f
            isEnabled = gyroSensor != null
            if (gyroSensor == null) text = "مستشعر الحركة غير متاح"
            setOnClickListener {
                airMouseEnabled = !airMouseEnabled
                text = if (airMouseEnabled) "إيقاف الماوس الهوائي" else "⌁  الماوس الهوائي"
                touchHint.text = if (airMouseEnabled) {
                    "✥\nلوحة الاتجاهات\nاسحب للتنقل • اضغط للموافقة"
                } else {
                    "⌖\nلوحة اللمس\nاسحب لتحريك المؤشر واضغط للاختيار"
                }
                touchPad.contentDescription = if (airMouseEnabled) {
                    "لوحة اتجاهات للتنقل والضغط للموافقة أثناء تشغيل الماوس الهوائي"
                } else {
                    "لوحة لمس لتحريك مؤشر التلفاز"
                }
                if (airMouseEnabled) startAirMouse() else stopAirMouse()
                setStatus(if (airMouseEnabled) "الماوس الهوائي مفعّل — اللوحة للتنقل والاختيار" else "التحكم بلوحة اللمس")
            }
        }
        bottom.addView(appShortcuts, LinearLayout.LayoutParams(dp(150), dp(48)).apply { marginEnd = dp(5) })
        bottom.addView(airToggle, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        // Three small TV-app shortcuts share one horizontal line with the air-mouse toggle.
        root.addView(deck, LinearLayout.LayoutParams(-1, dp(174)).apply { topMargin = dp(3) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(3); bottomMargin = dp(5) })

        setContentView(root)
    }

    private fun showSettingsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val airLabel = TextView(this).apply { textSize = 15f; setTextColor(0xff15243a.toInt()) }
        val airSeek = SeekBar(this).apply {
            max = 2000
            progress = ((prefs.getFloat("air_mouse_sensitivity", 1498f) - 500f).toInt()).coerceIn(0, 2000)
        }
        airLabel.text = "حساسية الماوس الهوائي: ${500 + airSeek.progress}"
        airSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                airLabel.text = "حساسية الماوس الهوائي: ${500 + progress}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val touchLabel = TextView(this).apply { textSize = 15f; setTextColor(0xff15243a.toInt()) }
        val touchSeek = SeekBar(this).apply {
            max = 20
            progress = ((prefs.getFloat("touchpad_sensitivity", 1.0f) - 0.5f) * 10f).toInt().coerceIn(0, 20)
        }
        touchLabel.text = "حساسية لوحة اللمس: " + String.format(java.util.Locale.US, "%.1f", 0.5f + touchSeek.progress / 10f) + "×"
        touchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                touchLabel.text = "حساسية لوحة اللمس: " + String.format(java.util.Locale.US, "%.1f", 0.5f + progress / 10f) + "×"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        content.addView(airLabel)
        content.addView(airSeek, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(touchLabel)
        content.addView(touchSeek, LinearLayout.LayoutParams(-1, dp(44)))
        AlertDialog.Builder(this)
            .setTitle("إعدادات وصلة")
            .setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                prefs.edit()
                    .putFloat("air_mouse_sensitivity", (500 + airSeek.progress).toFloat())
                    .putFloat("touchpad_sensitivity", 0.5f + touchSeek.progress / 10f)
                    .apply()
                setStatus("تم حفظ إعدادات وصلة")
            }
            .setNeutralButton("تحديث التطبيق") { _, _ -> checkForUpdates() }
            .show()
    }

    private fun checkForUpdates() {
        setStatus("جارٍ التحقق من تحديث وصلة...")
        Thread({
            var latestVersion = ""
            var downloadUrl = "https://github.com/loay94an/wasla/releases/latest"
            var errorMessage: String? = null
            try {
                val connection = (URL("https://api.github.com/repos/loay94an/wasla/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Wasla-Android")
                }
                try {
                    if (connection.responseCode == 200) {
                        val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        latestVersion = release.optString("tag_name", "").removePrefix("v")
                        val assets = release.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.optJSONObject(i) ?: continue
                                if (asset.optString("name").startsWith("wasla-eshare-remote") &&
                                    asset.optString("name").endsWith(".apk")) {
                                    downloadUrl = asset.optString("browser_download_url", downloadUrl)
                                    break
                                }
                            }
                        }
                    } else {
                        errorMessage = "لا توجد نسخة منشورة بعد أو تعذر الوصول إلى صفحة الإصدارات."
                    }
                } finally { connection.disconnect() }
            } catch (_: Exception) {
                errorMessage = "تعذر الاتصال بخدمة التحديث. تحقق من الإنترنت وحاول مجدداً."
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val current = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removePrefix("v")
                val hasNew = latestVersion.isNotBlank() && latestVersion != current
                val message = when {
                    errorMessage != null -> errorMessage!!
                    hasNew -> "تتوفر نسخة أحدث: $latestVersion\nالإصدار المثبت: $current"
                    else -> "أنت تستخدم أحدث إصدار منشور ($current)."
                }
                AlertDialog.Builder(this)
                    .setTitle("تحديث وصلة")
                    .setMessage(message)
                    .setPositiveButton(if (hasNew) "تنزيل التحديث" else "فتح صفحة الإصدارات") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(downloadUrl))) }
                        catch (_: Exception) { setStatus("تعذر فتح رابط التحديث") }
                    }
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }, "wasla-update-check").start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            8110 -> {
                if (resultCode == RESULT_OK) {
                    val contents = data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
                    if (!contents.isNullOrBlank()) handleWaslaQr(contents)
                    else setStatus("لم يتم العثور على بيانات داخل رمز QR")
                } else {
                    setStatus("تم إلغاء مسح رمز QR أو لم يُمنح إذن الكاميرا")
                }
            }
            702 -> {
                if (resultCode == RESULT_OK) {
                    val uris = mutableListOf<android.net.Uri>()
                    data?.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
                    }
                    if (uris.isEmpty()) data?.data?.let(uris::add)
                    if (uris.isEmpty()) return
                    uris.forEach { uploadFileToTv(it) }
                }
            }
            703 -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data ?: return
                    downloadTvFileTo(uri)
                }
            }
            701 -> {
                if (resultCode != RESULT_OK) {
                    setStatus("تم إلغاء الاستماع أو لم يصل رد من خدمة الصوت")
                    return
                }
                val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (spoken.isNullOrBlank()) {
                    setStatus("لم يتم التعرف على الكلام؛ حاول مجدداً")
                } else {
                    setStatus("تم التعرف: $spoken")
                    processSpokenText(spoken)
                }
            }
        }
    }

    // Match the known EShare remote flow: launch Android's speech UI directly.
    // The recognizer app owns microphone permission; Wasla does not capture audio itself.
    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-JO")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-JO")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن للتحكم بالتلفاز أو لإدخال نص")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            setStatus("🎙 استمع الآن…")
            startActivityForResult(intent, 701)
        } catch (e: Exception) {
            setStatus("تعذر تشغيل التعرف الصوتي: ${e.message ?: "تحقق من خدمة Google"}")
        }
    }

    private fun processSpokenText(raw: String) {
        val spoken = raw.trim()
        val command = spoken.lowercase(java.util.Locale("ar"))
        when {
            command.contains("الرئيسية") || command.contains("هوم") -> sendKey(3)
            command.contains("رجوع") || command.contains("عودة") -> sendKey(4)
            command.contains("يمين") -> sendKey(22)
            command.contains("يسار") -> sendKey(21)
            command.contains("فوق") || command.contains("أعلى") -> sendKey(19)
            command.contains("تحت") || command.contains("أسفل") -> sendKey(20)
            command.contains("موافق") || command.contains("اختيار") -> sendKey(23)
            command.contains("ارفع الصوت") || command.contains("الصوت أعلى") -> sendKey(24)
            command.contains("اخفض الصوت") || command.contains("الصوت أقل") -> sendKey(25)
            command.contains("كتم") -> sendKey(164)
            command.contains("طاقة") || command.contains("إيقاف") -> sendKey(26)
            command.contains("افتح المساعد الصوتي") || command.contains("شغل المساعد الصوتي") -> sendKey(231)
            command.startsWith("ابحث في يوتيوب عن ") || command.startsWith("ابحث عن ") -> {
                val query = spoken.substringAfter("عن ").trim()
                if (query.isNotBlank()) {
                    sendKey(84)
                    gestureHandler.postDelayed({ sendText(query) }, 900L)
                }
            }
            else -> {
                sendText(spoken)
            }
        }
    }

    private data class TvApp(val name: String, val packageName: String)

    // EShare's original remote uses GETAPPINFOSEVNET and a little-endian
    // length-prefixed binary list; STARTAPPEVNET launches the selected TV package.
    private fun readTvAppList(): List<TvApp> {
        val socket = tcp ?: throw IllegalStateException("التلفاز غير متصل")
        synchronized(socketReadLock) {
            socket.soTimeout = 5000
            try {
                synchronized(socketWriteLock) {
                    val stream = output ?: throw IllegalStateException("جلسة التحكم غير جاهزة")
                    stream.write("GETAPPINFOSEVNET\r\n1\r\n\r\n".toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                val input = socket.getInputStream()
                val header = ByteArray(4)
                var got = 0
                while (got < 4) {
                    val count = input.read(header, got, 4 - got)
                    if (count < 0) throw IllegalStateException("انقطع رد قائمة التطبيقات")
                    got += count
                }
                fun intLe(bytes: ByteArray, offset: Int): Int =
                    (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                        ((bytes[offset + 3].toInt() and 0xff) shl 24)
                val total = intLe(header, 0)
                if (total !in 4..8_000_000) throw IllegalStateException("حجم قائمة التطبيقات غير صالح: $total")
                val packet = ByteArray(total)
                System.arraycopy(header, 0, packet, 0, 4)
                got = 4
                while (got < total) {
                    val count = input.read(packet, got, total - got)
                    if (count < 0) throw IllegalStateException("قائمة التطبيقات وصلت بشكل غير مكتمل")
                    got += count
                }
                val apps = ArrayList<TvApp>()
                var offset = 4
                fun readField(): ByteArray {
                    if (offset + 4 > total) throw IllegalStateException("بيانات التطبيقات غير مكتملة")
                    val length = intLe(packet, offset)
                    offset += 4
                    if (length < 0 || length > total - offset) throw IllegalStateException("حقل تطبيق غير صالح")
                    val bytes = packet.copyOfRange(offset, offset + length)
                    offset += length
                    return bytes
                }
                while (offset < total) {
                    val name = readField().toString(Charsets.UTF_8)
                    val packageName = readField().toString(Charsets.UTF_8)
                    readField() // version name
                    if (offset + 8 > total) throw IllegalStateException("بيانات إصدار التطبيق غير مكتملة")
                    val versionCodeLength = intLe(packet, offset)
                    offset += 4
                    if (versionCodeLength < 0 || versionCodeLength > total - offset) throw IllegalStateException("إصدار تطبيق غير صالح")
                    offset += versionCodeLength
                    readField() // icon bytes
                    if (name.isNotBlank() && packageName.isNotBlank()) apps.add(TvApp(name, packageName))
                }
                return apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase(java.util.Locale.getDefault()) }
            } finally {
                try { socket.soTimeout = 250 } catch (_: Exception) { }
            }
        }
    }

    private fun chooseTvAppShortcut(slot: Int, button: Button) {
        setStatus("جارٍ جلب تطبيقات التلفاز...")
        io.execute {
            try {
                val apps = readTvAppList()
                runOnUiThread {
                    if (apps.isEmpty()) {
                        Toast.makeText(this, "لم يُرجع التلفاز قائمة تطبيقات", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("اختر تطبيقاً من التلفاز للاختصار $slot")
                        .setItems(apps.map { it.name }.toTypedArray()) { _, which ->
                            val app = apps[which]
                            prefs.edit()
                                .putString("tv_app_shortcut_${slot}_pkg", app.packageName)
                                .putString("tv_app_shortcut_${slot}_label", app.name)
                                .apply()
                            button.text = app.name
                            button.contentDescription = "فتح تطبيق التلفاز ${app.name}"
                            setStatus("تم تعيين ${app.name} للاختصار $slot")
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "تعذر جلب تطبيقات التلفاز: ${e.message ?: "تحقق من الاتصال"}", Toast.LENGTH_LONG).show()
                    setStatus("تعذر قراءة تطبيقات التلفاز")
                }
            }
        }
    }

    private fun launchTvAppShortcut(slot: Int) {
        val packageName = prefs.getString("tv_app_shortcut_${slot}_pkg", null)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, "اضغط مطولاً لاختيار تطبيق من التلفاز", Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            try {
                writePacketWithReconnect("STARTAPPEVNET\r\n$packageName\r\n\r\n")
                setStatus("تم إرسال أمر فتح التطبيق إلى التلفاز")
            } catch (e: Exception) {
                setStatus("تعذر فتح التطبيق: ${e.message ?: "تحقق من الاتصال"}")
            }
        }
    }

    private fun openScreenShareSettings() {
        for (action in listOf("android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS")) {
            try {
                startActivity(Intent(action))
                setStatus("اختر التلفاز من قائمة الإرسال لبدء مشاركة الشاشة")
                return
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "مشاركة الشاشة غير متاحة من إعدادات هذا الهاتف", Toast.LENGTH_LONG).show()
    }

    private fun pickFileForTv() {
        AlertDialog.Builder(this)
            .setTitle("ملفات التلفاز")
            .setItems(arrayOf("إرسال ملفات من الهاتف إلى التلفاز", "استعراض وإدارة ملفات التلفاز")) { _, which ->
                if (which == 0) {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    try { startActivityForResult(intent, 702) }
                    catch (_: Exception) { Toast.makeText(this, "تعذر فتح مدير الملفات", Toast.LENGTH_LONG).show() }
                } else {
                    showTvFiles()
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun openFileChannel(): Pair<Socket, Pair<DataInputStream, DataOutputStream>> {
        val ip = prefs.getString("wasla_tv_ip", "") ?: ""
        val code = prefs.getString("wasla_tv_code", "") ?: ""
        require(ip.isNotBlank() && code.matches(Regex("\\d{6}"))) { "الهاتف غير مقترن بمستقبل وصلة" }
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(ip, 38472), 6000)
        socket.soTimeout = 120000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        output.writeUTF("PAIR $code")
        output.flush()
        check(input.readUTF() == "OK WASLA-FILES/1") { "رفض مستقبل وصلة اتصال الملفات" }
        return socket to (input to output)
    }

    private fun uploadFileToTv(uri: android.net.Uri) {
        io.execute {
            try {
                val meta = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)
                var name = "wasla-file.bin"
                var size = -1L
                meta?.use { if (it.moveToFirst()) {
                    val ni = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (ni >= 0) name = it.getString(ni) ?: name
                    if (si >= 0 && !it.isNull(si)) size = it.getLong(si)
                } }
                if (size < 0) size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                require(size in 0L..536870912L) { "حجم الملف غير معروف أو يتجاوز 512 ميغابايت" }
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val name64 = android.util.Base64.encodeToString(name.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val mime64 = android.util.Base64.encodeToString(mime.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                setStatus("جارٍ تجهيز نقل الملف: $name")
                val (socket, streams) = openFileChannel()
                socket.use {
                    val (input, output) = streams
                    output.writeUTF("PUT ${tvFolderStack.lastOrNull() ?: "ROOT"} $name64 $mime64 $size")
                    contentResolver.openInputStream(uri)?.use { source ->
                        val buffer = ByteArray(32 * 1024)
                        var remaining = size
                        var reported = -1
                        while (remaining > 0) {
                            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) throw java.io.EOFException("انتهى الملف قبل اكتمال الإرسال")
                            output.write(buffer, 0, count)
                            remaining -= count
                            val percent = (((size - remaining) * 100) / size).toInt()
                            if (percent >= reported + 5 || remaining == 0L) {
                                reported = percent
                                setStatus("إرسال $name إلى التلفاز: $percent%")
                            }
                        }
                        output.flush()
                    } ?: throw IllegalStateException("تعذر فتح الملف المحدد")
                    val response = input.readUTF()
                    check(response == "STORED") { response }
                }
                setStatus("تم نقل الملف إلى التلفاز: $name")
            } catch (e: Exception) {
                setStatus("فشل نقل الملف: ${e.message ?: "خطأ في الاتصال"}")
            }
        }
    }

    private fun showTvFiles() {
        io.execute {
            try {
                val (socket, streams) = openFileChannel()
                val rows = socket.use {
                    val (input, output) = streams
                    val directoryId = tvFolderStack.lastOrNull()
                    output.writeUTF(if (directoryId == null) "LIST" else "LIST $directoryId")
                    output.flush()
                    val response = input.readUTF()
                    check(response.startsWith("FILES ")) { response }
                    val json = String(android.util.Base64.decode(response.removePrefix("FILES "), android.util.Base64.NO_WRAP), Charsets.UTF_8)
                    org.json.JSONArray(json)
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val labels = Array(rows.length()) { i ->
                        val item = rows.getJSONObject(i)
                        val size = item.optLong("size", 0L)
                        "${item.optString("name")}  •  ${if (item.optBoolean("directory")) "مجلد" else android.text.format.Formatter.formatFileSize(this, size)}"
                    }
                    AlertDialog.Builder(this).setTitle("ملفات التلفاز").setItems(labels) { _, index ->
                        val item = rows.optJSONObject(index) ?: return@setItems
                        if (item.optBoolean("directory")) {
                            tvFolderStack.add(item.optString("id"))
                            showTvFiles()
                        } else {
                            val id = item.optString("id")
                            val name = item.optString("name", "wasla-download.bin")
                            AlertDialog.Builder(this).setTitle(name)
                                .setItems(arrayOf("تنزيل إلى الهاتف", "إعادة تسمية", "حذف من التلفاز")) { _, action ->
                                    when (action) {
                                        0 -> {
                                            pendingDownloadId = id
                                            pendingDownloadName = name
                                            val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                addCategory(Intent.CATEGORY_OPENABLE)
                                                type = item.optString("mime", "application/octet-stream")
                                                putExtra(Intent.EXTRA_TITLE, name)
                                            }
                                            startActivityForResult(create, 703)
                                        }
                                        1 -> renameTvFile(id, name)
                                        2 -> deleteTvFile(id, name)
                                    }
                                }.setNegativeButton("إلغاء", null).show()
                        }
                    }.setPositiveButton(if (tvFolderStack.isEmpty()) "إغلاق" else "المجلد السابق") { _, _ ->
                        if (tvFolderStack.isNotEmpty()) {
                            tvFolderStack.removeAt(tvFolderStack.lastIndex)
                            showTvFiles()
                        }
                    }
                    .setNeutralButton("مجلد جديد") { _, _ -> createTvFolder() }
                    .setNegativeButton("إغلاق", null).show()
                }
            } catch (e: Exception) {
                setStatus("تعذر استعراض ملفات التلفاز: ${e.message ?: "تحقق من الاقتران ومجلد المشاركة"}")
            }
        }
    }

    private fun createTvFolder() {
        val field = EditText(this).apply { hint = "اسم المجلد"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("إنشاء مجلد").setView(field)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إنشاء") { _, _ ->
                val name = field.text.toString().trim()
                io.execute {
                    try {
                        val (socket, streams) = openFileChannel()
                        val result = socket.use {
                            val (input, output) = streams
                            val encoded = android.util.Base64.encodeToString(name.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                            output.writeUTF("MKDIR ${tvFolderStack.lastOrNull() ?: "ROOT"} $encoded"); output.flush()
                            input.readUTF()
                        }
                        setStatus(if (result == "CREATED") "تم إنشاء المجلد" else "تعذر إنشاء المجلد: $result")
                        if (result == "CREATED") showTvFiles()
                    } catch (e: Exception) { setStatus("فشل إنشاء المجلد: ${e.message}") }
                }
            }.show()
    }

    private fun renameTvFile(id: String, oldName: String) {
        val field = EditText(this).apply { setText(oldName); isSingleLine = true }
        AlertDialog.Builder(this).setTitle("إعادة تسمية").setView(field)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val name = field.text.toString().trim()
                io.execute {
                    try {
                        val (socket, streams) = openFileChannel()
                        val result = socket.use {
                            val (input, output) = streams
                            val encodedName = android.util.Base64.encodeToString(name.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                            output.writeUTF("RENAME $id $encodedName"); output.flush()
                            input.readUTF()
                        }
                        setStatus(if (result == "RENAMED") "تمت إعادة التسمية" else "تعذرت إعادة التسمية: $result")
                        if (result == "RENAMED") showTvFiles()
                    } catch (e: Exception) { setStatus("فشلت إعادة التسمية: ${e.message}") }
                }
            }.show()
    }

    private fun deleteTvFile(id: String, name: String) {
        io.execute {
            try {
                val (socket, streams) = openFileChannel()
                val result = socket.use {
                    val (input, output) = streams
                    output.writeUTF("DELETE $id"); output.flush()
                    input.readUTF()
                }
                setStatus(if (result == "DELETED") "تم حذف $name من التلفاز" else "تعذر حذف الملف: $result")
            } catch (e: Exception) { setStatus("فشل الحذف: ${e.message}") }
        }
    }

    private fun downloadTvFileTo(destination: android.net.Uri) {
        val id = pendingDownloadId ?: return
        io.execute {
            try {
                val (socket, streams) = openFileChannel()
                socket.use {
                    val (input, output) = streams
                    output.writeUTF("GET $id"); output.flush()
                    val header = input.readUTF().split(' ')
                    check(header.size == 4 && header[0] == "DATA") { header.joinToString(" ") }
                    val size = header[1].toLong()
                    require(size in 0L..536870912L) { "حجم ملف غير صالح" }
                    val target = contentResolver.openOutputStream(destination) ?: error("تعذر فتح وجهة الحفظ")
                    target.use { sink ->
                        val buffer = ByteArray(32 * 1024)
                        var remaining = size
                        var reported = -1
                        while (remaining > 0) {
                            val count = minOf(buffer.size.toLong(), remaining).toInt()
                            input.readFully(buffer, 0, count)
                            sink.write(buffer, 0, count)
                            remaining -= count
                            val percent = if (size == 0L) 100 else (((size - remaining) * 100) / size).toInt()
                            if (percent >= reported + 5 || remaining == 0L) {
                                reported = percent
                                setStatus("تنزيل $pendingDownloadName إلى الهاتف: $percent%")
                            }
                        }
                    }
                }
                setStatus("اكتمل تنزيل الملف: $pendingDownloadName")
            } catch (e: Exception) { setStatus("فشل تنزيل الملف: ${e.message}") }
            finally { pendingDownloadId = null }
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

    private fun discoverAndConnect(manualIp: String? = null, interactive: Boolean = false) {
        if (!reconnecting.compareAndSet(false, true)) return
        runOnUiThread {
            connectButton.isEnabled = false
            connectionIpLabel?.text = "⌁ جارٍ الاتصال..."
        }
        setStatus(if (interactive) "جارٍ البحث عن الشاشات المتاحة..." else "جارٍ استعادة الاتصال بالشاشة...")
        io.execute {
            try {
                closeConnection()
                val found = if (manualIp != null) {
                    val savedIp = prefs.getString("ip", null)
                    val port = if (manualIp == savedIp) prefs.getInt("port", 8121) else discoverPort(manualIp)
                    Device("شاشة محددة", manualIp, port)
                } else {
                    val savedIp = prefs.getString("ip", "") ?: ""
                    val savedPort = prefs.getInt("port", 8121)
                    // During recovery, verify the last known receiver first. This avoids
                    // a full subnet sweep every time Wi-Fi briefly drops a TCP session.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!interactive && savedIp.isNotBlank() && isControlPortOpen(savedIp, savedPort)) {
                        Device(prefs.getString("device_name", "التلفاز") ?: "التلفاز", savedIp, savedPort)
                    } else if (!interactive && savedIp.isNotBlank() && now - lastFullDiscoveryAt < 25000L) {
                        // Retry the last receiver directly between full discovery sweeps.
                        Device(prefs.getString("device_name", "التلفاز") ?: "التلفاز", savedIp, savedPort)
                    } else {
                        lastFullDiscoveryAt = now
                        discover(interactive)
                    }
                }
                connect(found)
            } catch (e: Exception) {
                runOnUiThread { connectionIpLabel?.text = "⌁ غير متصل" }
                setStatus("تعذر الاتصال: ${e.message ?: "تحقق من Wi-Fi"}")
            } finally {
                try { discoveryLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
                discoveryLock = null
                runOnUiThread { connectButton.isEnabled = true }
                reconnecting.set(false)
            }
        }
    }

    private data class Device(val name: String, val ip: String, val port: Int, val latencyMs: Long = -1L)

    private fun showIpEditor() {
        val editor = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "مثال: 192.168.1.50"
            setText(prefs.getString("ip", "") ?: "")
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل عنوان IP للشاشة")
            .setMessage("أدخل عنوان IPv4 للشاشة، ثم اضغط اتصال.")
            .setView(editor)
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("مسح") { _, _ ->
                prefs.edit().remove("ip").remove("port").apply()
                connectionIpLabel?.text = "⌁ غير متصل"
                Toast.makeText(this, "تم مسح عنوان الشاشة المحفوظ", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("اتصال") { _, _ ->
                val ip = editor.text.toString().trim()
                val valid = ip.matches(Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$"))
                if (!valid) {
                    Toast.makeText(this, "عنوان IP غير صحيح", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString("ip", ip).apply()
                    discoverAndConnect(manualIp = ip)
                }
            }
            .show()
    }

    private fun measureConnectLatency(device: Device): Long {
        val started = android.os.SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(device.ip, device.port), 900)
            }
            (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    // Discover UPnP/DIAL receivers even when the EShare application is not open.
    private fun discoverSsdpScreens(): List<Device> {
        val candidates = java.util.concurrent.ConcurrentHashMap<String, Device>()
        val targets = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "ssdp:all"
        )
        try {
            DatagramSocket().use { udp ->
                udp.soTimeout = 350
                for (target in targets) {
                    val request = (
                        "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: " + "\"ssdp:discover\"" + "\r\n" +
                        "MX: 1\r\nST: $target\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                    try {
                        udp.send(DatagramPacket(request, request.size,
                            InetAddress.getByName("239.255.255.250"), 1900))
                    } catch (_: Exception) { }
                }
                val deadline = System.currentTimeMillis() + 2600
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udp.receive(packet)
                        val response = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                        val st = response.lineSequence().firstOrNull { it.startsWith("ST:", true) }
                            ?.substringAfter(":")?.trim().orEmpty()
                        val server = response.lineSequence().firstOrNull { it.startsWith("SERVER:", true) }
                            ?.substringAfter(":")?.trim().orEmpty()
                        val usn = response.lineSequence().firstOrNull { it.startsWith("USN:", true) }
                            ?.substringAfter(":")?.trim().orEmpty()
                        val renderer = st.contains("MediaRenderer", true) ||
                            st.contains("dial-multiscreen", true) ||
                            server.contains("Roku", true) ||
                            server.contains("Samsung", true) ||
                            server.contains("Android", true) ||
                            server.contains("LG", true)
                        if (!renderer) continue
                        val ip = packet.address.hostAddress ?: continue
                        val name = when {
                            server.contains("Roku", true) -> "Roku TV"
                            server.contains("Samsung", true) -> "Samsung TV"
                            server.contains("LG", true) -> "LG TV"
                            server.contains("Android", true) -> "Android TV"
                            st.contains("dial-multiscreen", true) -> "مستقبل بث"
                            else -> "شاشة على الشبكة"
                        }
                        candidates["$ip:$usn"] = Device(name, ip, 8121)
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) { }
        return candidates.values.distinctBy { it.ip }.sortedBy { it.name }
    }

    private fun showScreensWithoutEShare(devices: List<Device>) {
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            val details = devices.joinToString("\n\n") {
                "• ${it.name}\n${it.ip}\nموجودة على الشبكة — خدمة EShare غير ظاهرة"
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("تم اكتشاف شاشة على الشبكة")
                .setMessage(details + "\n\nتم التعرف على وجود الشاشة دون تشغيل EShare، لكن التحكم بها يحتاج خدمة تحكم متوافقة.")
                .setPositiveButton("حسناً") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(30, TimeUnit.SECONDS)
    }

    private fun packet(): ByteArray {
        val name = "FindECloudBox".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(50).order(ByteOrder.BIG_ENDIAN)
            .putInt(0).putInt(0).putInt(0).putInt(name.size).putInt(0).put(name).array()
    }

    @Suppress("DEPRECATION")
    private fun discover(interactive: Boolean = false): Device {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo
        try {
            discoveryLock?.let { if (it.isHeld) it.release() }
            discoveryLock = wifi.createMulticastLock("wasla-eshare-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }

        data class Subnet(val local: InetAddress, val broadcast: InetAddress, val prefix: Int)
        val subnets = mutableListOf<Subnet>()
        if (dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            try {
                val local = InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dhcp.ipAddress).array())
                val mask = dhcp.netmask
                val broadcastValue = (dhcp.ipAddress and mask) or mask.inv()
                val broadcast = InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(broadcastValue).array())
                subnets.add(Subnet(local, broadcast, Integer.bitCount(mask)))
            } catch (_: Exception) { }
        }
        // Include Wi-Fi, hotspot, Ethernet and other active IPv4 interfaces.
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
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

        // Keep every discovered screen instead of returning the first UDP reply.
        val devices = java.util.concurrent.ConcurrentHashMap<String, Device>()
        fun addDevice(device: Device) {
            if (device.ip.isNotBlank() && device.port in 1..65535) {
                devices["${device.ip}:${device.port}"] = device
            }
        }

        try {
            val bytes = packet()
            DatagramSocket(null).use { udp ->
                udp.reuseAddress = true
                udp.bind(InetSocketAddress(48689))
                udp.broadcast = true
                udp.soTimeout = 350
                val targets = (subnets.map { it.broadcast } + InetAddress.getByName("255.255.255.255"))
                    .distinctBy { it.hostAddress }
                for (target in targets) {
                    try { udp.send(DatagramPacket(bytes, bytes.size, target, 48689)) } catch (_: Exception) { }
                }
                val deadline = System.currentTimeMillis() + 2800
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        udp.receive(response)
                        val payload = String(response.data, 0, response.length, Charsets.UTF_8)
                        if (payload.startsWith("ECloudBox")) {
                            val fields = payload.split(":")
                            val port = fields.getOrNull(2)?.toIntOrNull() ?: 8121
                            val ip = response.address.hostAddress ?: continue
                            addDevice(Device(fields.getOrElse(1) { "شاشة EShare" }, ip, port))
                        }
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) { }

        // Fallback for EShare devices that do not answer UDP discovery.
        // Probe every host in each active local subnet; do not assume a saved IP.
        setStatus("جارٍ فحص الشبكة لاكتشاف جميع الشاشات...")
        val pool = Executors.newFixedThreadPool(64)
        val tasks = mutableListOf<java.util.concurrent.Future<*>>()
        for (subnet in subnets) {
            val prefix = subnet.prefix.coerceIn(1, 30).coerceAtMost(24)
            val local = ByteBuffer.wrap(subnet.local.address).order(ByteOrder.LITTLE_ENDIAN).int
            val mask = -1 shl (32 - prefix)
            val network = local and mask
            val broadcast = network or mask.inv()
            for (host in (network + 1) until broadcast) {
                if (host == local) continue
                val ip = listOf(host and 255, (host ushr 8) and 255, (host ushr 16) and 255, (host ushr 24) and 255).joinToString(".")
                tasks.add(pool.submit {
                    if (isControlPortOpen(ip, 8121)) addDevice(Device("شاشة EShare", ip, 8121))
                })
            }
        }
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline && tasks.any { !it.isDone }) Thread.sleep(80)
        pool.shutdownNow()

        val found = devices.values
            .map { device -> device.copy(latencyMs = measureConnectLatency(device)) }
            .filter { it.latencyMs != Long.MAX_VALUE }
            .sortedWith(compareBy<Device> { it.latencyMs }.thenBy { it.name }.thenBy { it.ip })
        if (found.isEmpty()) {
            val networkScreens = discoverSsdpScreens()
            if (networkScreens.isNotEmpty()) {
                showScreensWithoutEShare(networkScreens)
                throw IllegalStateException("تم اكتشاف الشاشة على الشبكة، لكن خدمة التحكم EShare غير متاحة.")
            }
            throw IllegalStateException("لم يتم العثور على شاشة. تأكد من اتصال الهاتف والشاشة بالشبكة نفسها وأن اكتشاف الأجهزة المحلية مسموح.")
        }
        val savedIp = prefs.getString("ip", "") ?: ""
        val selected = when {
            found.size == 1 -> found.first()
            interactive -> chooseDiscoveredDevice(found)
            savedIp.isNotBlank() && found.any { it.ip == savedIp } -> found.first { it.ip == savedIp }
            else -> found.first()
        }
        prefs.edit().putString("ip", selected.ip).putInt("port", selected.port).putString("device_name", selected.name).apply()
        runOnUiThread { ipInput.setText(selected.ip) }
        return selected
    }

    private fun chooseDiscoveredDevice(devices: List<Device>): Device {
        val result = java.util.concurrent.atomic.AtomicReference<Device?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            val labels = devices.mapIndexed { index, device ->
                val distance = if (device.latencyMs >= 0L) "استجابة ${device.latencyMs} ms" else "استجابة غير مقاسة"
                "${index + 1}. ${device.name}\n${device.ip}  •  منفذ ${device.port}  •  $distance"
            }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("اختر الشاشة المراد الاتصال بها")
                .setItems(labels) { _, which ->
                    if (which in devices.indices) result.set(devices[which])
                    latch.countDown()
                }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        if (!latch.await(90, TimeUnit.SECONDS)) {
            throw IllegalStateException("انتهت مهلة اختيار الشاشة")
        }
        return result.get() ?: throw IllegalStateException("لم يتم اختيار شاشة")
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

    // EShare requires a small feature/auth handshake before keyboard CONTENT
    // packets are accepted. Complete request/response reads BEFORE starting the
    // background socket reader so it cannot steal handshake replies.
    private fun ensureLocalHttpServer(): Int {
        localHttpServer?.let { if (!it.isClosed) return it.localPort }
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(8888))
            }
        } catch (_: Exception) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
        localHttpServer = server
        localHttpThread = Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { client ->
                        client.soTimeout = 1200
                        val input = client.getInputStream()
                        var matched = 0
                        var count = 0
                        while (count++ < 8192 && matched < 4) {
                            val b = input.read()
                            if (b < 0) break
                            matched = when {
                                matched == 0 && b == 13 -> 1
                                matched == 1 && b == 10 -> 2
                                matched == 2 && b == 13 -> 3
                                matched == 3 && b == 10 -> 4
                                b == 13 -> 1
                                else -> 0
                            }
                        }
                        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        client.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    if (server.isClosed) break
                }
            }
        }, "wasla-eshare-http").apply { isDaemon = true; start() }
        return server.localPort
    }

    private fun handshakeRead(socket: Socket, timeoutMs: Int = 1800): String {
        socket.soTimeout = timeoutMs
        return try {
            val buffer = ByteArray(4096)
            val count = socket.getInputStream().read(buffer)
            if (count > 0) String(buffer, 0, count, Charsets.UTF_8) else ""
        } catch (_: SocketTimeoutException) {
            ""
        }
    }

    private fun handshakeWrite(socket: Socket, packet: String) {
        val stream = socket.getOutputStream()
        stream.write(packet.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    private fun connect(device: Device) = synchronized(connectionLock) {
        // Preserve the current healthy session. A new TCP session is created
        // only after the old one is confirmed closed or the user selects a
        // different endpoint through the explicit discovery flow.
        val active = tcp
        val activeIp = (active?.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
        val activePort = (active?.remoteSocketAddress as? InetSocketAddress)?.port
        if (active != null && !active.isClosed && active.isConnected &&
            !active.isInputShutdown && !active.isOutputShutdown &&
            activeIp == device.ip && activePort == device.port) return@synchronized
        closeConnection()
        val httpPort = ensureLocalHttpServer()
        val socket = Socket()
        socket.connect(InetSocketAddress(device.ip, device.port), 4000)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        tcp = socket
        output = socket.getOutputStream()
        prefs.edit().putString("ip", device.ip).putInt("port", device.port).apply()
        gyroCursorX = framebufferWidth / 2f
        gyroCursorY = framebufferHeight / 2f

        try {
            handshakeWrite(socket, "getFeatures\r\nBoxlightPro\r\n\r\n")
            handshakeRead(socket, 1200)
            handshakeWrite(socket, "auth\r\n${android.os.Environment.getExternalStorageDirectory().absolutePath} $httpPort onelong\r\n\r\n")
            // EShare replies to each setup command. Consume the auth response before
            // sending getServerInfo, otherwise the next read may consume the wrong reply
            // and leave the keyboard/input channel in an unauthenticated state.
            handshakeRead(socket, 1800)
            handshakeWrite(socket, "getServerInfo\r\n1\r\n\r\n")
            handshakeRead(socket, 1800)
            handshakeWrite(socket, "GETFBINFO\r\n1\r\n\r\n")
            val fbInfo = handshakeRead(socket, 1800).trim()
            val dimensions = fbInfo.split(Regex("""\r?\n"""))
            val width = dimensions.getOrNull(0)?.trim()?.toFloatOrNull()
            val height = dimensions.getOrNull(1)?.trim()?.toFloatOrNull()
            if (width != null && height != null && width > 0f && height > 0f) {
                framebufferWidth = width
                framebufferHeight = height
                gyroCursorX = width / 2f
                gyroCursorY = height / 2f
            }
        } catch (e: Exception) {
            // A failed socket write/handshake must never be reported as connected.
            try { socket.close() } catch (_: Exception) { }
            if (tcp === socket) {
                tcp = null
                output = null
            }
            throw e
        } finally {
            try { socket.soTimeout = 0 } catch (_: Exception) { }
        }
        // A successful handshake is the single point at which this session becomes
        // authoritative. Cancel any delayed network-recovery work and reset retry state.
        networkRecoveryHandler.removeCallbacks(networkRecoveryTask)
        lastEshareKeepAliveAt = android.os.SystemClock.elapsedRealtime()
        startSocketReader(socket)
        reconnectAttempts = 0
        nextReconnectAt = 0L
        runOnUiThread { connectionIpLabel?.text = "⌁  ${device.ip}" }
        setStatus("متصل: ${device.name} — ${device.ip}:${device.port}")
    }

    private fun startAirMouse() {
        val sensor = gyroSensor ?: return
        lastGyroTimestamp = 0L
        smoothedGyroDx = 0f
        smoothedGyroDy = 0f
        sensorManager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopAirMouse() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(gyroListener)
        lastGyroTimestamp = 0L
        smoothedGyroDx = 0f
        smoothedGyroDy = 0f
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
            // EShare's original air mouse sends GYROSCOPEEVENT with an ABSOLUTE
            // 1280x720 cursor position. AIRMOUSEEVNET is not the gyro protocol.
            // Remove stationary gyro bias, integrate angular velocity, and keep
            // the cursor position between packets instead of restarting at center.
            val rateX = if (kotlin.math.abs(event.values[2]) < 0.008f) 0f else event.values[2]
            val rateY = if (kotlin.math.abs(event.values[0]) < 0.008f) 0f else event.values[0]
            // Increase cursor travel while retaining time-based integration so
            // sensitivity stays consistent across different sensor sample rates.
            val sensitivity = prefs.getFloat("air_mouse_sensitivity", 1498f).coerceIn(500f, 2500f)
            val targetDx = -rateX * dt * sensitivity
            val targetDy = -rateY * dt * sensitivity
            // Faster response with enough filtering to avoid a jittery pointer.
            smoothedGyroDx += (targetDx - smoothedGyroDx) * 0.65f
            smoothedGyroDy += (targetDy - smoothedGyroDy) * 0.65f
            if (smoothedGyroDx != 0f || smoothedGyroDy != 0f) {
                gyroCursorX = (gyroCursorX + smoothedGyroDx).coerceIn(0f, framebufferWidth)
                gyroCursorY = (gyroCursorY + smoothedGyroDy).coerceIn(0f, framebufferHeight)
                sendGyroPosition(gyroCursorX, gyroCursorY)
            }
        }
    }

    private fun sendTouchMouseEvent(
        dx: Float,
        dy: Float,
        action: Int,
        viewWidth: Int = 1,
        viewHeight: Int = 1
    ) {
        if (action == 2) {
            pendingTouchDx += dx
            pendingTouchDy += dy
            if (System.currentTimeMillis() - lastTouchMoveAt < 16L) return
            lastTouchMoveAt = System.currentTimeMillis()
        } else if (action != 1) {
            return
        }
        // Flush any final sub-16ms movement on finger-up, then update a
        // persistent ABSOLUTE framebuffer position instead of relative deltas.
        val moveDx = pendingTouchDx
        val moveDy = pendingTouchDy
        pendingTouchDx = 0f
        pendingTouchDy = 0f
        if (moveDx == 0f && moveDy == 0f) return
        val touchSensitivity = prefs.getFloat("touchpad_sensitivity", 1.0f).coerceIn(0.5f, 2.5f)
        val scaledX = moveDx * framebufferWidth * touchSensitivity / viewWidth.coerceAtLeast(1)
        val scaledY = moveDy * framebufferHeight * touchSensitivity / viewHeight.coerceAtLeast(1)
        gyroCursorX = (gyroCursorX + scaledX).coerceIn(0f, framebufferWidth)
        gyroCursorY = (gyroCursorY + scaledY).coerceIn(0f, framebufferHeight)
        if (waslaTvSocket == null) sendGyroPosition(gyroCursorX, gyroCursorY)
    }

    private fun sendPointerClick() {
        // EShare uses action 2 to position/move the absolute pointer,
        // action 0 for press, and action 1 for release. Re-send the exact
        // current coordinates immediately before the click to avoid stale
        // cursor coordinates on firmware that processes pointer events late.
        val x = gyroCursorX
        val y = gyroCursorY
        if (sendWaslaTap(x, y)) return
        airMouseIo.execute {
            try {
                writePointerPacket("GYROSCOPEEVENT\r\n$x\r\n$y\r\n2\r\n\r\n")
                writePointerPacket("GYROSCOPEEVENT\r\n$x\r\n$y\r\n0\r\n\r\n")
                writePointerPacket("GYROSCOPEEVENT\r\n$x\r\n$y\r\n1\r\n\r\n")
            } catch (e: Exception) {
                closeConnection()
                setStatus("تعذر النقر: ${e.message ?: "أعد الاتصال"}")
            }
        }
    }

    private fun sendGyroPosition(x: Float, y: Float) {
        if (waslaTvSocket != null) return
        synchronized(gyroPositionLock) {
            pendingGyroPosition = x to y
            if (gyroPositionWriteQueued) return
            gyroPositionWriteQueued = true
        }
        // Coalesce sensor updates to the newest absolute position; never queue stale deltas.
        airMouseIo.execute { drainGyroPosition() }
    }

    private fun drainGyroPosition() {
        try {
            while (true) {
                val position = synchronized(gyroPositionLock) {
                    val next = pendingGyroPosition
                    pendingGyroPosition = null
                    if (next == null) gyroPositionWriteQueued = false
                    next
                } ?: break
                writePointerPacket("GYROSCOPEEVENT\r\n${position.first}\r\n${position.second}\r\n2\r\n\r\n")
            }
        } catch (e: Exception) {
            synchronized(gyroPositionLock) {
                pendingGyroPosition = null
                gyroPositionWriteQueued = false
            }
            closeConnection()
            setStatus("تعذر تحريك الماوس الهوائي: ${e.message ?: "أعد الاتصال"}")
        }
    }

    private fun writePointerPacket(packet: String) {
        writePacketWithReconnect(packet)
    }

    private fun writePacketWithReconnect(packet: String) {
        synchronized(connectionLock) {
            fun writeOnce() {
                if (tcp?.isConnected != true || tcp?.isClosed != false || tcp?.isOutputShutdown == true) {
                    val ip = prefs.getString("ip", null) ?: throw IllegalStateException("ابحث عن التلفاز أولاً")
                    val port = prefs.getInt("port", 8121)
                    connect(Device("التلفاز", ip, port))
                }
                synchronized(socketWriteLock) {
                    val stream = output ?: throw IllegalStateException("جلسة التحكم غير جاهزة")
                    stream.write(packet.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            }
            try {
                writeOnce()
            } catch (first: Exception) {
                closeConnection()
                try {
                    val ip = prefs.getString("ip", null) ?: throw first
                    val port = prefs.getInt("port", 8121)
                    connect(Device("التلفاز", ip, port))
                    writeOnce()
                    setStatus("أُعيد الاتصال بالتلفاز تلقائياً")
                } catch (second: Exception) {
                    closeConnection()
                    nextReconnectAt = 0L
                    setStatus("انقطع الاتصال — ستتم استعادة الشاشة نفسها تلقائياً: ${second.message ?: "تحقق من الشبكة"}")
                    throw second
                }
            }
        }
    }

    private fun showConnectionOptions() {
        AlertDialog.Builder(this)
            .setTitle("طريقة الاتصال")
            .setItems(arrayOf("التعرّف التلقائي على الشاشة", "الاتصال بمسح رمز QR")) { _, choice ->
                when (choice) {
                    0 -> discoverWaslaThenEShare()
                    1 -> startWaslaQrScanner()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private data class WaslaReceiverCandidate(val deviceId: String, val ip: String, val port: Int, val latencyMs: Long)

    private fun discoverWaslaThenEShare(interactive: Boolean = false) {
        if (!reconnecting.compareAndSet(false, true)) return
        connectButton.isEnabled = false
        setStatus("جارٍ البحث عن مستقبلات وصلة والشاشات المتوافقة...")
        io.execute {
            val found = LinkedHashMap<String, WaslaReceiverCandidate>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 250
                    val probe = "WASLA_DISCOVER/1".toByteArray(Charsets.UTF_8)
                    val broadcast = DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), 38470)
                    socket.send(broadcast)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 2200L
                    val buffer = ByteArray(512)
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val fields = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim().split(' ')
                            if (fields.size >= 3 && fields[0] == "WASLA-TV-DISCOVER/1") {
                                val id = fields[1]
                                // The UDP source address is the actual responding TV interface;
                                // do not trust an arbitrary first interface address from the TV.
                                val ip = packet.address.hostAddress.orEmpty()
                                val port = fields[2].toIntOrNull()
                                if (id.isNotBlank() && ip.isNotBlank() && port == 38471) {
                                    found["$id@$ip"] = WaslaReceiverCandidate(id, ip, port, measureConnectLatency(Device("وصلة TV", ip, port)))
                                }
                            }
                        } catch (_: SocketTimeoutException) { }
                    }
                }
            } catch (_: Exception) { }
            runOnUiThread {
                reconnecting.set(false)
                connectButton.isEnabled = true
                if (found.isNotEmpty()) {
                    showWaslaReceiverChoices(found.values.sortedBy { it.latencyMs })
                } else {
                    setStatus("لم يُعثر على مستقبل وصلة — جارٍ البحث عن شاشات EShare...")
                    discoverAndConnect(interactive = interactive)
                }
            }
        }
    }

    private fun showWaslaReceiverChoices(devices: List<WaslaReceiverCandidate>) {
        val labels = devices.mapIndexed { index, device ->
            "${index + 1}. وصلة TV\\n${device.ip}  •  ${device.latencyMs} ms"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر مستقبل وصلة")
            .setItems(labels) { _, which ->
                if (which in devices.indices) showWaslaPinPrompt(devices[which])
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showWaslaPinPrompt(device: WaslaReceiverCandidate) {
        val codeInput = EditText(this).apply {
            hint = "رمز الاقتران الظاهر على التلفاز"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("تأكيد الاقتران")
            .setMessage("تم العثور على مستقبل وصلة على الشبكة. أدخل رمز التأكيد الظاهر على شاشة التلفاز.")
            .setView(codeInput)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("اقتران") { _, _ ->
                connectWaslaReceiver(device.ip, codeInput.text.toString().trim(), device.deviceId)
            }
            .show()
    }

    private fun startWaslaQrScanner() {
        try {
            startActivityForResult(Intent(this, QrScannerActivity::class.java), 8110)
        } catch (e: Exception) {
            setStatus("تعذر فتح ماسح QR: ${e.message ?: "تحقق من الكاميرا"}")
        }
    }

    private fun handleWaslaQr(raw: String) {
        try {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != "wasla" || uri.host != "pair") {
                throw IllegalArgumentException("رمز QR ليس رمز اقتران وصلة")
            }
            val ip = uri.getQueryParameter("ip").orEmpty()
            val code = uri.getQueryParameter("code").orEmpty()
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 38471
            val deviceId = uri.getQueryParameter("id").orEmpty()
            val transport = uri.getQueryParameter("transport").orEmpty()
            val expectedPeerId = "wasla-tv-" + deviceId.replace("-", "").lowercase()
            val suppliedPeerId = uri.getQueryParameter("peer").orEmpty()
            if (port != 38471 || !code.matches(Regex("\\d{6}")) ||
                !deviceId.matches(Regex("[0-9a-fA-F-]{36}")) ||
                (transport.isNotBlank() && transport != "peerjs") ||
                (suppliedPeerId.isNotBlank() && suppliedPeerId != expectedPeerId)) {
                throw IllegalArgumentException("بيانات اقتران وصلة غير مكتملة أو غير صالحة")
            }
            prefs.edit().putString("wasla_tv_ip", ip).putString("wasla_tv_code", code)
                .putString("wasla_tv_id", deviceId).putString("wasla_tv_peer_id", expectedPeerId).apply()
            startPeerJsTransport(deviceId, code)
            if (ip.isNotBlank()) {
                setStatus("تم حفظ التلفاز — جارٍ الاتصال المحلي والإنترنت عبر PeerJS...")
                connectWaslaReceiver(ip, code, deviceId)
            } else {
                setStatus("تم الاقتران — جارٍ إنشاء اتصال PeerJS المباشر عبر الإنترنت...")
            }
        } catch (e: Exception) {
            setStatus("تعذر قراءة رمز وصلة: ${e.message ?: "رمز غير صالح"}")
        }
    }

    private fun connectWaslaReceiver(ip: String, code: String, expectedDeviceId: String? = null) {
        if (ip.isBlank() || !code.matches(Regex("\\d{6}"))) {
            setStatus("بيانات الاقتران غير صالحة")
            return
        }
        if (!waslaConnecting.compareAndSet(false, true)) return
        waslaLinkExecutor.execute {
            closeWaslaReceiver()
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(ip, 38471), 5000)
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write("PAIR $code\n")
                writer.flush()
                val greeting = reader.readLine()
                if (greeting?.startsWith("OK WASLA-TV/1") != true) {
                    socket.close()
                    throw IllegalStateException("رفض التلفاز رمز الاقتران")
                }
                // Bind the scanned QR identity to the endpoint so a stale/forged
                // QR cannot silently pair with a different receiver on the same IP.
                writer.write("INFO\n")
                writer.flush()
                val info = reader.readLine().orEmpty().split(' ')
                if (info.size < 3 || info[0] != "WASLA-TV/1" ||
                    (!expectedDeviceId.isNullOrBlank() && info[1] != expectedDeviceId)) {
                    socket.close()
                    throw IllegalStateException("هوية مستقبل وصلة لا تطابق رمز QR")
                }
                val controlReady = info.getOrNull(3) == "CONTROL_READY"
                socket.soTimeout = 20000
                synchronized(waslaTvLock) {
                    waslaTvSocket = socket
                    waslaTvOutput = socket.getOutputStream()
                }
                val now = android.os.SystemClock.elapsedRealtime()
                lastWaslaPongAt = now
                lastWaslaPingAt = now
                prefs.edit().putString("wasla_tv_ip", ip).putString("wasla_tv_code", code)
                    .putString("wasla_tv_id", info[1]).apply()
                startPeerJsTransport(info[1], code)
                runOnUiThread { connectionIpLabel?.text = "⌁  $ip" }
                setStatus(
                    if (controlReady) "تم الاقتران والتحكم جاهز — $ip"
                    else "تم الاقتران، لكن التحكم غير مفعّل على التلفاز. افتح وصلة TV وفعّل خدمة إمكانية الوصول."
                )
                Thread({
                    try {
                        while (!socket.isClosed) {
                            val line = try { reader.readLine() } catch (_: SocketTimeoutException) { continue } ?: break
                            if (line == "PONG") {
                                lastWaslaPongAt = android.os.SystemClock.elapsedRealtime()
                            } else if (line == "ACTION_UNAVAILABLE") {
                                setStatus("الاتصال قائم، لكن يجب تفعيل خدمة وصلة من إعدادات إمكانية الوصول على التلفاز")
                            } else if (line == "BAD_ACTION") {
                                setStatus("رفض مستقبل وصلة الأمر؛ تحقق من توافق إصدار التطبيقين")
                            } else if (line == "ACTION_OK") {
                                setStatus("تم تنفيذ الأمر على التلفاز")
                            } else if (line.startsWith("WASLA-TV/1")) {
                                setStatus("اتصال وصلة نشط: $ip")
                            }
                        }
                    } catch (_: Exception) { }
                    if (waslaTvSocket === socket) {
                        closeWaslaReceiver()
                        setStatus("انقطع اتصال مستقبل وصلة — ستتم إعادة المحاولة تلقائياً")
                    }
                }, "wasla-tv-link-reader").apply { isDaemon = true; start() }
            } catch (e: Exception) {
                closeWaslaReceiver()
                setStatus(if (peerJsConnected) "الاتصال المحلي غير متاح، لكن التحكم عبر الإنترنت جاهز"
                    else "تعذر الاتصال بمستقبل وصلة: ${e.message ?: "تحقق من الشبكة ورمز الاقتران"}")
            } finally {
                waslaConnecting.set(false)
            }
        }
    }

    private fun startWaslaLinkMonitor() {
        if (!waslaMonitorStarted.compareAndSet(false, true)) return
        waslaLinkMonitor.scheduleWithFixedDelay({
            val socket = waslaTvSocket
            if (socket == null || socket.isClosed || !socket.isConnected) {
                val ip = prefs.getString("wasla_tv_ip", "") ?: ""
                val code = prefs.getString("wasla_tv_code", "") ?: ""
                if (ip.isNotBlank() && code.matches(Regex("\\d{6}")) && !waslaConnecting.get()) {
                    connectWaslaReceiver(ip, code)
                }
                return@scheduleWithFixedDelay
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastWaslaPongAt > 40000L) {
                closeWaslaReceiver()
                setStatus("انتهت مهلة اتصال وصلة — جارٍ إعادة الاتصال...")
                return@scheduleWithFixedDelay
            }
            if (now - lastWaslaPingAt >= 15000L) {
                try {
                    synchronized(waslaTvLock) {
                        val active = waslaTvSocket ?: return@scheduleWithFixedDelay
                        val out = waslaTvOutput ?: return@scheduleWithFixedDelay
                        if (active.isClosed) throw IllegalStateException("socket closed")
                        out.write("PING\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                        lastWaslaPingAt = now
                    }
                } catch (_: Exception) {
                    closeWaslaReceiver()
                    setStatus("انقطع اتصال مستقبل وصلة — جارٍ إعادة المحاولة...")
                }
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun closeWaslaReceiver() {
        synchronized(waslaTvLock) {
            try { waslaTvOutput?.close() } catch (_: Exception) { }
            try { waslaTvSocket?.close() } catch (_: Exception) { }
            waslaTvOutput = null
            waslaTvSocket = null
        }
    }

    private fun sendKey(key: Int) {
        val waslaKey = when (key) {
            19 -> "DPAD_UP"
            20 -> "DPAD_DOWN"
            21 -> "DPAD_LEFT"
            22 -> "DPAD_RIGHT"
            23 -> "SELECT"
            4 -> "BACK"
            3 -> "HOME"
            187 -> "RECENTS"
            24 -> "VOLUME_UP"
            25 -> "VOLUME_DOWN"
            164 -> "MUTE"
            else -> null
        }
        if (waslaKey != null && sendWaslaAction("KEY $waslaKey")) return
        io.execute {
            try {
                writePacketWithReconnect("KEYEVENT\r\n$key\r\n\r\n")
            } catch (e: Exception) {
                setStatus("تعذر إرسال الأمر: ${e.message ?: "الاتصال غير متاح"}")
            }
        }
    }

    /** Prefer the verified LAN socket; use PeerJS WebRTC DataChannel outside the LAN. */
    private fun sendWaslaAction(command: String): Boolean {
        synchronized(waslaTvLock) {
            val socket = waslaTvSocket
            val out = waslaTvOutput
            val pongAge = android.os.SystemClock.elapsedRealtime() - lastWaslaPongAt
            if (socket != null && out != null && !socket.isClosed && socket.isConnected && pongAge <= 25000L) {
                try {
                    out.write("ACTION $command\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    return true
                } catch (_: Exception) {
                    closeWaslaReceiver()
                }
            } else if (socket != null && pongAge > 25000L) {
                closeWaslaReceiver()
            }
        }
        if (!peerJsConnected) return false
        val web = peerJsWebView ?: return false
        val id = "${System.currentTimeMillis()}-${peerJsMessageId.incrementAndGet()}"
        val timeout = Runnable {
            peerActionTimeouts.remove(id)
            setStatus("لم يصل تأكيد تنفيذ الأمر من التلفاز — تحقق من الاتصال")
        }
        peerActionTimeouts.put(id, timeout)?.let { gestureHandler.removeCallbacks(it) }
        gestureHandler.postDelayed(timeout, 10000L)
        val js = "window.WaslaPeer && window.WaslaPeer.send(" +
            JSONObject.quote(command) + "," + JSONObject.quote(id) + ")"
        return try {
            web.post {
                web.evaluateJavascript(js) { result ->
                    if (result != "true") {
                        peerActionTimeouts.remove(id)?.let { gestureHandler.removeCallbacks(it) }
                        runOnUiThread { setStatus("تعذر إرسال الأمر عبر قناة الإنترنت — لم يؤكد الاتصال استلامه") }
                    } else {
                        runOnUiThread { setStatus("جارٍ تنفيذ الأمر على التلفاز…") }
                    }
                }
            }
        } catch (_: Exception) {
            peerActionTimeouts.remove(id)?.let { gestureHandler.removeCallbacks(it) }
            false
        }
    }

    private fun initPeerJsWebView() {
        if (peerJsWebView != null) return
        try {
            val web = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(PeerJsBridge(), "WaslaNative")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        peerJsPageLoaded = true
                        val id = peerJsDeviceId
                        val code = peerJsPairCode
                        if (id.isNotBlank() && code.isNotBlank()) startPeerJsTransport(id, code)
                    }
                }
            }
            peerJsWebView = web
            findViewById<ViewGroup>(android.R.id.content).addView(
                web, android.widget.FrameLayout.LayoutParams(dp(1), dp(1))
            )
            web.loadUrl("file:///android_asset/peerjs-transport.html")
        } catch (e: Exception) {
            peerJsConnected = false
            setStatus("تعذر تهيئة قناة التحكم عبر الإنترنت: ${e.message ?: "WebView غير متاح"}")
        }
    }

    private fun startPeerJsTransport(deviceId: String, code: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { startPeerJsTransport(deviceId, code) }
            return
        }
        if (!deviceId.matches(Regex("[0-9a-fA-F-]{36}")) || !code.matches(Regex("\\d{6}"))) return
        peerJsDeviceId = deviceId
        peerJsPairCode = code
        peerJsConnected = false
        if (peerJsWebView == null) initPeerJsWebView()
        if (!peerJsPageLoaded) return
        val peerId = "wasla-tv-" + deviceId.replace("-", "").lowercase()
        val options = JSONObject().put("role", "mobile").put("deviceId", deviceId)
            .put("pin", code).put("peerId", peerId)
        peerJsWebView?.evaluateJavascript("window.WaslaPeer && window.WaslaPeer.start($options)", null)
    }

    inner class PeerJsBridge {
        @JavascriptInterface fun onPeerId(id: String) { }
        @JavascriptInterface fun onPeerStatus(value: String) {
            runOnUiThread {
                peerJsConnected = value == "connected"
                when (value) {
                    "connected" -> setStatus("اتصال التحكم عبر الإنترنت جاهز")
                    "disconnected" -> setStatus("انقطع مسار الإنترنت — ستتم إعادة المحاولة")
                    "library-unavailable" -> setStatus("تعذر تحميل مكتبة الاتصال المجانية — تحقق من الإنترنت")
                    "signaling-reconnecting" -> setStatus("جارٍ استعادة اتصال وصلة عبر الإنترنت...")
                }
            }
        }
        @JavascriptInterface fun onPeerReady(controlReady: Boolean) {
            runOnUiThread {
                peerJsConnected = true
                setStatus(if (controlReady) "تم الاتصال بالتلفاز عبر الإنترنت — التحكم جاهز"
                    else "اتصل الهاتف بالتلفاز عبر الإنترنت، لكن يجب تفعيل خدمة وصلة من إعدادات إمكانية الوصول على التلفاز")
            }
        }
        @JavascriptInterface fun onPeerError(value: String) {
            runOnUiThread { setStatus("تعذر إنشاء اتصال الإنترنت ($value) — سيُعاد المحاولة") }
        }
        @JavascriptInterface fun onPeerActionResult(id: String, result: String) {
            runOnUiThread {
                peerActionTimeouts.remove(id)?.let { gestureHandler.removeCallbacks(it) }
                setStatus(if (result == "ACTION_OK") "تم تنفيذ الأمر على التلفاز"
                    else "تعذر تنفيذ الأمر على التلفاز: $result")
            }
        }
    }

    private fun sendWaslaTap(x: Float, y: Float): Boolean {
        val nx = (x / framebufferWidth.coerceAtLeast(1f) * 10000f).coerceIn(0f, 10000f)
        val ny = (y / framebufferHeight.coerceAtLeast(1f) * 10000f).coerceIn(0f, 10000f)
        return sendWaslaAction("TAP ${nx.toInt()} ${ny.toInt()}")
    }

    private fun sendWaslaSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        fun norm(value: Float, extent: Float) =
            (value / extent.coerceAtLeast(1f) * 10000f).coerceIn(0f, 10000f).toInt()
        return sendWaslaAction(
            "SWIPE ${norm(x1, framebufferWidth)} ${norm(y1, framebufferHeight)} " +
                "${norm(x2, framebufferWidth)} ${norm(y2, framebufferHeight)} ${duration.coerceIn(80L, 2000L)}"
        )
    }

    private fun sendPointerHold(pressed: Boolean) {
        val x = gyroCursorX
        val y = gyroCursorY
        if (pressed && (waslaTvSocket != null || peerJsConnected)) {
            val nx = (x / framebufferWidth.coerceAtLeast(1f) * 10000f).toInt().coerceIn(0, 10000)
            val ny = (y / framebufferHeight.coerceAtLeast(1f) * 10000f).toInt().coerceIn(0, 10000)
            if (sendWaslaAction("LONG_PRESS $nx $ny 700")) return
        } else if (!pressed && (waslaTvSocket != null || peerJsConnected)) return
        val action = if (pressed) 0 else 1
        airMouseIo.execute {
            try {
                writePointerPacket("GYROSCOPEEVENT\r\n$x\r\n$y\r\n$action\r\n\r\n")
            } catch (_: Exception) { }
        }
    }

    // Match EShare's original keyboard protocol: update composing text on every edit
    // using the previous text length, then commit once when the user presses Send/Done.
    private fun sendComposingText(previousLength: Int, value: String) {
        val text = value.replace("\r", "").replace("\n", " ")
        io.execute {
            try {
                writePacketWithReconnect("CONTENT\r\n0\r\n$previousLength $text\r\n\r\n")
            } catch (e: Exception) {
                setStatus("تعذر تحديث النص: " + (e.message ?: "تحقق من الاتصال"))
            }
        }
    }

    private fun sendText(value: String) {
        val text = value.replace("\r", "").replace("\n", " ")
        if (text.isBlank()) return
        val encoded = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        if (sendWaslaAction("TEXT $encoded")) {
            setStatus("أُرسل النص إلى مستقبل وصلة")
            return
        }
        io.execute {
            try {
                // EShare keyboard expects composing text followed by a commit.
                // Send both packets in order on the same socket write queue.
                writePacketWithReconnect("CONTENT\r\n0\r\n0 $text\r\n\r\nCONTENT\r\n1\r\n0 $text\r\n\r\n")
                setStatus("أُرسل النص إلى التلفاز")
            } catch (e: Exception) {
                setStatus("تعذر إرسال النص: " + (e.message ?: "تحقق من الاتصال"))
            }
        }
    }

    // Read the TCP stream continuously so a remote FIN/reset is detected even
    // when no remote-control button is being pressed. EShare sends no required
    // replies to ordinary key/pointer packets, so unsolicited bytes are drained.
    private fun startSocketReader(socket: Socket) {
        socketReader.execute {
            var disconnected = false
            try {
                socket.soTimeout = 4000
                val input = socket.getInputStream()
                val buffer = ByteArray(1024)
                while (!socket.isClosed && !Thread.currentThread().isInterrupted) {
                    try {
                        socket.soTimeout = 250
                        val count = synchronized(socketReadLock) { input.read(buffer) }
                        if (count < 0) {
                            disconnected = true
                            break
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // A timeout alone is not a disconnect; the periodic port probe
                        // checks whether the TV is still reachable on the current network.
                    }
                }
            } catch (_: Exception) {
                disconnected = !socket.isClosed
            } finally {
                if (disconnected && tcp === socket) {
                    closeConnection()
                    nextReconnectAt = 0L
                    setStatus("انقطع اتصال التلفاز — جارٍ استعادته إلى الشاشة نفسها...")
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                availableLocalNetworks.add(network)
                lastDefaultNetwork = network
                // Do not disturb a live EShare socket on route changes.
                if (!hasActiveEshareSession() && !reconnecting.get()) {
                    scheduleNetworkRecovery("عودة اتصال الشبكة")
                }
            }
            override fun onLost(network: Network) {
                availableLocalNetworks.remove(network)
                if (lastDefaultNetwork == network) lastDefaultNetwork = null
                // A local-network callback is not proof that the existing
                // TCP route died. Keep the session; retry only after socket loss.
                if (availableLocalNetworks.isEmpty() && !hasActiveEshareSession()) {
                    scheduleNetworkRecovery("فُقد اتصال الشبكة المحلية")
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val isLocal = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (isLocal) availableLocalNetworks.add(network)
                else availableLocalNetworks.remove(network)
                if (isLocal && !hasActiveEshareSession() && !reconnecting.get()) {
                    scheduleNetworkRecovery("عادت شبكة Wi-Fi المحلية")
                }
            }
        }
        try {
            // Observe local transports directly; Wi-Fi without internet is still
            // a valid route to a TV and must not be considered disconnected.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
            lastDefaultNetwork = null
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                    availableLocalNetworks.add(network)
                    lastDefaultNetwork = network
                }
            }
        } catch (e: Exception) {
            setStatus("تعذرت مراقبة تغيّر الشبكة: " + (e.message ?: "سيستمر البحث التلقائي"))
        }
    }

    private fun scheduleNetworkRecovery(reason: String) {
        if (hasActiveEshareSession()) return
        networkRecoveryHandler.removeCallbacks(networkRecoveryTask)
        setStatus(reason + " — جارٍ تجهيز إعادة الاتصال...")
        networkRecoveryHandler.postDelayed(networkRecoveryTask, 1400L)
    }

    /** A healthy session wins over discovery, callbacks, and retry timers. */
    private fun hasActiveEshareSession(): Boolean {
        val socket = tcp ?: return false
        return !socket.isClosed && socket.isConnected &&
            !socket.isInputShutdown && !socket.isOutputShutdown
    }

    private fun startConnectionMonitor() {
        // Keep the selected endpoint pinned. A dropped TCP session is repaired
        // against that exact IP/port; automatic recovery must never select another TV.
        connectionMonitor.scheduleWithFixedDelay({
            val socket = tcp
            if (hasActiveEshareSession() && socket != null) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastEshareKeepAliveAt >= 15000L) {
                    try {
                        synchronized(socketWriteLock) {
                            if (tcp === socket && !socket.isClosed) {
                                val stream = output ?: throw IllegalStateException("قناة التحكم مغلقة")
                                stream.write("GETFBINFO\r\n1\r\n\r\n".toByteArray(Charsets.UTF_8))
                                stream.flush()
                                lastEshareKeepAliveAt = now
                            }
                        }
                    } catch (_: Exception) {
                        closeConnection()
                        nextReconnectAt = 0L
                        setStatus("انقطع الاتصال — جارٍ استعادته إلى الشاشة نفسها...")
                    }
                }
            } else {
                scheduleAutomaticReconnect("الاتصال غير نشط — استعادة الشاشة المحددة")
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    /**
     * Reconnect only to the user's last selected endpoint. Discovery is reserved
     * for an explicit Connect tap; background recovery never changes the TV.
     */
    private fun scheduleAutomaticReconnect(reason: String, immediate: Boolean = false) {
        // Never enqueue a retry while a working EShare session is already owned.
        if (hasActiveEshareSession()) {
            reconnectAttempts = 0
            nextReconnectAt = 0L
            networkRecoveryHandler.removeCallbacks(networkRecoveryTask)
            return
        }
        val ip = prefs.getString("ip", "") ?: ""
        if (ip.isBlank() || isFinishing) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!immediate && now < nextReconnectAt) return
        if (!reconnecting.compareAndSet(false, true)) return
        val port = prefs.getInt("port", 8121)
        setStatus("$reason — جارٍ الاتصال بـ $ip:$port")
        io.execute {
            try {
                // Re-check on the serialized I/O thread: a manual connection may
                // have succeeded after this retry was queued.
                val active = tcp
                if (hasActiveEshareSession() && active != null) {
                    reconnectAttempts = 0
                    return@execute
                }
                closeConnection()
                connect(Device(prefs.getString("device_name", "التلفاز") ?: "التلفاز", ip, port))
                reconnectAttempts = 0
                nextReconnectAt = 0L
                setStatus("متصل مجدداً بالشاشة المحددة: $ip")
            } catch (e: Exception) {
                reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(6)
                val delaySeconds = (1L shl reconnectAttempts).coerceIn(2L, 60L)
                nextReconnectAt = android.os.SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(delaySeconds)
                closeConnection()
                setStatus("الشاشة $ip غير متاحة حالياً — ستتم إعادة المحاولة خلال $delaySeconds ثانية")
            } finally {
                reconnecting.set(false)
            }
        }
    }

    private fun closeConnection() = synchronized(connectionLock) {
        try { output?.close() } catch (_: Exception) { }
        try { tcp?.close() } catch (_: Exception) { }
        output = null
        tcp = null
    }

    override fun onDestroy() {
        gestureHandler.removeCallbacksAndMessages(null)
        networkRecoveryHandler.removeCallbacksAndMessages(null)
        try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { manager.unregisterNetworkCallback(it) }
        } catch (_: Exception) { }
        networkCallback = null
        connectionMonitor.shutdownNow()
        closeWaslaReceiver()
        peerJsConnected = false
        peerJsWebView?.let { web ->
            try { web.evaluateJavascript("window.WaslaPeer && window.WaslaPeer.stop()", null) } catch (_: Exception) { }
            try { (web.parent as? ViewGroup)?.removeView(web) } catch (_: Exception) { }
            try { web.stopLoading(); web.destroy() } catch (_: Exception) { }
        }
        peerJsWebView = null
        waslaLinkMonitor.shutdownNow()
        waslaLinkExecutor.shutdownNow()
        closeConnection()
        try { localHttpServer?.close() } catch (_: Exception) { }
        localHttpServer = null
        io.shutdownNow()
        airMouseIo.shutdownNow()
        socketReader.shutdownNow()
        super.onDestroy()
    }
}

package com.wasla.eshareremote

import android.app.Activity
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Typeface
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.io.OutputStream
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

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val connectionMonitor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val reconnecting = AtomicBoolean(false)
    private val connectionLock = Any()
    private val gestureHandler = Handler(Looper.getMainLooper())
    // Keep pointer traffic off the discovery/key-command executor to avoid input stalls.
    private val airMouseIo = Executors.newSingleThreadExecutor()
    private val socketReader = Executors.newSingleThreadExecutor()
    private val gyroPositionLock = Any()
    private val socketWriteLock = Any()
    // EShare framebuffer dimensions are queried from GETFBINFO after connecting.
    private var framebufferWidth = 1280f
    private var framebufferHeight = 720f
    private var gyroCursorX = 640f
    private var gyroCursorY = 360f
    private var pendingGyroPosition: Pair<Float, Float>? = null
    private var gyroPositionWriteQueued = false
    private var lastTouchMoveAt = 0L
    private var pendingTouchDx = 0f
    private var pendingTouchDy = 0f
    private var tcp: Socket? = null
    private var output: OutputStream? = null
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
    private lateinit var textInput: EditText
    private var suppressTextWatcher = false
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
        startConnectionMonitor()
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
        root.addView(header, lp(-1, dp(72)))

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
        var previousTextLength = 0
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previousTextLength = s?.length ?: 0
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextWatcher) {
                    sendComposingText(previousTextLength, s?.toString().orEmpty())
                }
            }
        })
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendTextButton.performClick()
                true
            } else false
        }
        root.addView(textEntry, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(7); bottomMargin = dp(3)
        })

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val castButton = makeButton("▣  مشاركة الشاشة", 48, true).apply {
            textSize = 13f
            setOnClickListener { openScreenShareSettings() }
        }
        shortcuts.addView(castButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        val filesButton = makeButton("⇧  نقل الملفات", 48).apply {
            textSize = 13f
            setOnClickListener { pickFileForTv() }
        }
        shortcuts.addView(filesButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(shortcuts, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(7); bottomMargin = dp(3) })

        // Keep manual IP connection available without letting it dominate the design.
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
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(12), 0, dp(12), 0)
            background = surface(0xff0c192d.toInt(), 14, 0xff263f61.toInt())
        }
        connection.addView(ipInput, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(7) })
        connectButton = makeButton("اتصال", 40, true).apply {
            textSize = 13f
            setOnClickListener { discoverAndConnect(ipInput.text.toString().trim().ifEmpty { null }) }
        }
        connection.addView(connectButton, LinearLayout.LayoutParams(dp(82), dp(40)))
        root.addView(connection, LinearLayout.LayoutParams(-1, dp(43)).apply {
            topMargin = dp(7); bottomMargin = dp(7)
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
            val savedLabel = prefs.getString("app_shortcut_${slot}_label", null)
            val shortcutButton = makeButton(savedLabel ?: "＋\nتطبيق $slot", 58).apply {
                textSize = 12f
                setOnClickListener { launchAppShortcut(slot) }
                setOnLongClickListener {
                    configureAppShortcut(slot, this)
                    true
                }
            }
            appShortcuts.addView(shortcutButton, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                if (slot > 1) setMargins(dp(4), 0, 0, 0)
            })
        }
        val airToggle = makeButton("⌁  الماوس الهوائي", 58, true).apply {
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
        bottom.addView(airToggle, LinearLayout.LayoutParams(-1, dp(58)))
        // Three configurable app shortcuts replace the voice-control button.
        root.addView(deck, LinearLayout.LayoutParams(-1, dp(174)).apply { topMargin = dp(3) })
        root.addView(appShortcuts, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(5) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(3); bottomMargin = dp(5) })

        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            702 -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data ?: return
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = contentResolver.getType(uri) ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newUri(contentResolver, "ملف للتلفاز", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "إرسال الملف إلى تطبيق على التلفاز"))
                    setStatus("اختر تطبيق استقبال الملفات على التلفاز إن كان مثبتاً")
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

    private fun configureAppShortcut(slot: Int, button: Button) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
                packageName to label
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(java.util.Locale.getDefault()) }
        if (apps.isEmpty()) {
            Toast.makeText(this, "لم يتم العثور على تطبيقات قابلة للتشغيل", Toast.LENGTH_LONG).show()
            return
        }
        val labels = apps.map { it.second }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("اختر تطبيقاً للاختصار $slot")
            .setItems(labels) { _, which ->
                val (packageName, label) = apps[which]
                prefs.edit()
                    .putString("app_shortcut_${slot}_pkg", packageName)
                    .putString("app_shortcut_${slot}_label", label)
                    .apply()
                button.text = label
                setStatus("تم تعيين $label للاختصار $slot")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun launchAppShortcut(slot: Int) {
        val packageName = prefs.getString("app_shortcut_${slot}_pkg", null)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, "اضغط مطولاً لاختيار تطبيق لهذا الزر", Toast.LENGTH_SHORT).show()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "التطبيق المحدد لم يعد مثبتاً؛ اضغط مطولاً لاختيار غيره", Toast.LENGTH_LONG).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح التطبيق: ${e.message ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(intent, 702) }
        catch (_: Exception) { Toast.makeText(this, "تعذر فتح مدير الملفات", Toast.LENGTH_LONG).show() }
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
        if (!reconnecting.compareAndSet(false, true)) return
        connectButton.isEnabled = false
        setStatus("جارٍ اكتشاف التلفاز عبر الشبكة...")
        io.execute {
            try {
                closeConnection()
                val found = if (manualIp != null) {
                    val savedIp = prefs.getString("ip", null)
                    val port = if (manualIp == savedIp) prefs.getInt("port", 8121) else discoverPort(manualIp)
                    Device("التلفاز", manualIp, port)
                } else discover()
                connect(found)
            } catch (e: Exception) {
                setStatus("تعذر الاتصال: ${e.message ?: "تحقق من Wi-Fi"}")
            } finally {
                try { discoveryLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
                discoveryLock = null
                runOnUiThread { connectButton.isEnabled = true }
                reconnecting.set(false)
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

        val found = devices.values.sortedWith(compareBy<Device> { it.name }.thenBy { it.ip })
        if (found.isEmpty()) {
            throw IllegalStateException("لم يتم العثور على شاشة EShare. تأكد أن الهاتف والشاشة على الشبكة نفسها وأن عزل الأجهزة غير مفعّل في الراوتر.")
        }
        val selected = if (found.size == 1) found.first() else chooseDiscoveredDevice(found)
        prefs.edit().putString("ip", selected.ip).putInt("port", selected.port).putString("device_name", selected.name).apply()
        runOnUiThread { ipInput.setText(selected.ip) }
        return selected
    }

    private fun chooseDiscoveredDevice(devices: List<Device>): Device {
        val result = java.util.concurrent.atomic.AtomicReference<Device?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            val labels = devices.mapIndexed { index, device ->
                "${index + 1}. ${device.name}\n${device.ip}  •  منفذ ${device.port}"
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
        closeConnection()
        val httpPort = ensureLocalHttpServer()
        val socket = Socket()
        socket.connect(InetSocketAddress(device.ip, device.port), 4000)
        socket.tcpNoDelay = true
        tcp = socket
        output = socket.getOutputStream()
        prefs.edit().putString("ip", device.ip).putInt("port", device.port).apply()
        gyroCursorX = framebufferWidth / 2f
        gyroCursorY = framebufferHeight / 2f

        try {
            handshakeWrite(socket, "getFeatures\r\nBoxlightPro\r\n\r\n")
            handshakeRead(socket, 1200)
            handshakeWrite(socket, "auth\r\n${android.os.Environment.getExternalStorageDirectory().absolutePath} $httpPort onelong\r\n\r\n")
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
        } catch (_: Exception) {
            // Keep the remote usable on firmware that omits one optional handshake reply.
        } finally {
            try { socket.soTimeout = 0 } catch (_: Exception) { }
        }
        startSocketReader(socket)
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
            val targetDx = -rateX * dt * 1498f
            val targetDy = -rateY * dt * 1498f
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
        val scaledX = moveDx * framebufferWidth * 1.0f / viewWidth.coerceAtLeast(1)
        val scaledY = moveDy * framebufferHeight * 1.0f / viewHeight.coerceAtLeast(1)
        gyroCursorX = (gyroCursorX + scaledX).coerceIn(0f, framebufferWidth)
        gyroCursorY = (gyroCursorY + scaledY).coerceIn(0f, framebufferHeight)
        sendGyroPosition(gyroCursorX, gyroCursorY)
    }

    private fun sendPointerClick() {
        // EShare uses action 2 to position/move the absolute pointer,
        // action 0 for press, and action 1 for release. Re-send the exact
        // current coordinates immediately before the click to avoid stale
        // cursor coordinates on firmware that processes pointer events late.
        val x = gyroCursorX
        val y = gyroCursorY
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
                    setStatus("انقطع الاتصال — جارٍ إعادة المحاولة: ${second.message ?: "تحقق من الشبكة"}")
                    throw second
                }
            }
        }
    }

    private fun sendKey(key: Int) {
        io.execute {
            try {
                writePacketWithReconnect("KEYEVENT\r\n$key\r\n\r\n")
            } catch (e: Exception) {
                setStatus("تعذر إرسال الأمر: ${e.message ?: "الاتصال غير متاح"}")
            }
        }
    }

    private fun sendPointerHold(pressed: Boolean) {
        val x = gyroCursorX
        val y = gyroCursorY
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
        io.execute {
            try {
                writePacketWithReconnect("CONTENT\r\n1\r\n0 $text\r\n\r\n")
                setStatus("تم إرسال النص إلى التلفاز")
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
                        val count = input.read(buffer)
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
                    setStatus("انقطع اتصال التلفاز — جارٍ استعادته تلقائياً...")
                }
            }
        }
    }

    private fun startConnectionMonitor() {
        connectionMonitor.scheduleWithFixedDelay({
            val ip = prefs.getString("ip", null) ?: return@scheduleWithFixedDelay
            val port = prefs.getInt("port", 8121)
            val reachable = isControlPortOpen(ip, port)
            if (!reachable) {
                if (tcp != null) {
                    closeConnection()
                    setStatus("انقطع الاتصال بالتلفاز — جارٍ البحث عنه مجدداً...")
                }
                if (!reconnecting.get()) discoverAndConnect()
            } else if (tcp == null || tcp?.isClosed != false) {
                if (!reconnecting.get()) discoverAndConnect()
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun closeConnection() = synchronized(connectionLock) {
        try { output?.close() } catch (_: Exception) { }
        try { tcp?.close() } catch (_: Exception) { }
        output = null
        tcp = null
    }

    override fun onDestroy() {
        gestureHandler.removeCallbacksAndMessages(null)
        connectionMonitor.shutdownNow()
        closeConnection()
        try { localHttpServer?.close() } catch (_: Exception) { }
        localHttpServer = null
        io.shutdownNow()
        airMouseIo.shutdownNow()
        socketReader.shutdownNow()
        super.onDestroy()
    }
}

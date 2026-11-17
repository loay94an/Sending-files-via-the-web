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
    // Keep pointer traffic off the discovery/key-command executor to avoid input stalls.
    private val airMouseIo = Executors.newSingleThreadExecutor()
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
        touchPad.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    lastTouchX = event.x; lastTouchY = event.y
                    moved = false
                    sendTouchMouseEvent(0f, 0f, 0)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.x - downX
                    val totalDy = event.y - downY
                    if (kotlin.math.abs(totalDx) + kotlin.math.abs(totalDy) > dp(1)) moved = true
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    if (moved && (dx != 0f || dy != 0f)) {
                        sendTouchMouseEvent(dx, dy, 2, view.width, view.height)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    sendTouchMouseEvent(0f, 0f, 1)
                    if (!moved) sendKey(23)
                    view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    sendTouchMouseEvent(0f, 0f, 1)
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
                        pressedSector = hitSector()
                        invalidate()
                        return pressedSector >= 0
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val next = hitSector()
                        if (next != pressedSector) { pressedSector = next; invalidate() }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val selected = hitSector()
                        val pressed = pressedSector
                        pressedSector = -1
                        invalidate()
                        if (selected >= 0 && selected == pressed) sendKey(if (selected == 4) 23 else keys[selected])
                        performClick()
                        return true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        pressedSector = -1
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
            contentDescription = "أزرار الاتجاهات حول زر OK"
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
        val voice = makeButton("🎙  التحكم الصوتي", 58).apply {
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
        bottom.addView(voice, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(5) })
        val airToggle = makeButton("⌁  الماوس الهوائي", 58, true).apply {
            textSize = 14f
            isEnabled = gyroSensor != null
            if (gyroSensor == null) text = "مستشعر الحركة غير متاح"
            setOnClickListener {
                airMouseEnabled = !airMouseEnabled
                text = if (airMouseEnabled) "إيقاف الماوس الهوائي" else "⌁  الماوس الهوائي"
                if (airMouseEnabled) startAirMouse() else stopAirMouse()
                setStatus(if (airMouseEnabled) "الماوس الهوائي مفعّل" else "التحكم بلوحة اللمس")
            }
        }
        bottom.addView(airToggle, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(5) })
        // Put the main navigation keys at the bottom, as requested, with voice/air-mouse below them.
        root.addView(deck, LinearLayout.LayoutParams(-1, dp(174)).apply { topMargin = dp(3) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(5); bottomMargin = dp(5) })

        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 702 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val share = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(contentResolver, "ملف للتلفاز", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "إرسال الملف إلى تطبيق على التلفاز"))
            setStatus("اختر تطبيق استقبال الملفات على التلفاز إن كان مثبتاً")
            return
        }
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
        // Match the official EShare client: request the actual framebuffer size
        // before sending pointer packets. A fixed 1280x720 scale can be rejected
        // or mapped incorrectly on TVs with another framebuffer resolution.
        try {
            socket.soTimeout = 1200
            output?.write("GETFBINFO\r\n1\r\n\r\n".toByteArray(Charsets.UTF_8))
            output?.flush()
            val response = ByteArray(1024)
            val count = socket.getInputStream().read(response)
            if (count > 0) {
                val payload = String(response, 0, count, Charsets.UTF_8)
                val values = Regex("\\d+").findAll(payload).mapNotNull { it.value.toIntOrNull() }.toList()
                if (values.size >= 2 && values[0] in 320..16384 && values[1] in 240..16384) {
                    framebufferWidth = values[0].toFloat()
                    framebufferHeight = values[1].toFloat()
                }
            }
        } catch (_: Exception) {
            // Keep a safe fallback if this EShare firmware does not answer GETFBINFO.
        } finally {
            try { socket.soTimeout = 500 } catch (_: Exception) { }
        }
        gyroCursorX = framebufferWidth / 2f
        gyroCursorY = framebufferHeight / 2f
        prefs.edit().putString("ip", device.ip).apply()
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
            val targetDx = -rateX * dt * 624f
            val targetDy = -rateY * dt * 624f
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
        var moveDx = dx
        var moveDy = dy
        if (action == 2) {
            // Keep every small finger movement instead of dropping events while
            // rate-limiting. Flush accumulated motion at roughly 60 Hz.
            pendingTouchDx += dx
            pendingTouchDy += dy
            val now = System.currentTimeMillis()
            if (now - lastTouchMoveAt < 16L) return
            lastTouchMoveAt = now
            moveDx = pendingTouchDx
            moveDy = pendingTouchDy
            pendingTouchDx = 0f
            pendingTouchDy = 0f
        } else {
            pendingTouchDx = 0f
            pendingTouchDy = 0f
            lastTouchMoveAt = 0L
        }
        // AIRMOUSEEVNET is EShare's relative pointer protocol. Accumulating
        // motion at a 60 Hz cadence makes the touchpad smoother without losing
        // small movements between transmitted packets.
        val scaledX = moveDx * framebufferWidth * 0.5f / viewWidth.coerceAtLeast(1)
        val scaledY = moveDy * framebufferHeight * 0.5f / viewHeight.coerceAtLeast(1)
        val packet = "AIRMOUSEEVNET\r\n$scaledX\r\n$scaledY\r\n$action\r\n\r\n"
        airMouseIo.execute {
            try {
                writePointerPacket(packet)
            } catch (e: Exception) {
                closeConnection()
                setStatus("تعذر تحريك لوحة اللمس: ${e.message ?: "أعد الاتصال"}")
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
                writePointerPacket("GYROSCOPEEVENT\r\n${position.first}\r\n${position.second}\r\n-1\r\n\r\n")
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
        if (tcp?.isConnected != true || tcp?.isClosed != false) {
            val ip = prefs.getString("ip", null) ?: throw IllegalStateException("ابحث عن التلفاز أولاً")
            connect(Device("التلفاز", ip, discoverPort(ip)))
        }
        synchronized(socketWriteLock) {
            output?.write(packet.toByteArray(Charsets.UTF_8))
            output?.flush()
        }
    }

    private fun sendKey(key: Int) {
        io.execute {
            try {
                if (tcp?.isConnected != true || tcp?.isClosed != false) {
                    val ip = prefs.getString("ip", null) ?: throw IllegalStateException("ابحث عن التلفاز أولاً")
                    connect(Device("التلفاز", ip, discoverPort(ip)))
                }
                // Pointer and key packets share one TCP stream. Serialize every write
                // so a key event can never split an AIRMOUSEEVNET packet.
                synchronized(socketWriteLock) {
                    output?.write("KEYEVENT\r\n$key\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output?.flush()
                }
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
        airMouseIo.shutdownNow()
        super.onDestroy()
    }
}

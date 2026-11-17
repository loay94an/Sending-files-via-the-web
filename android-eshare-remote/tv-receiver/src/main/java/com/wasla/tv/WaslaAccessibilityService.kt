package com.wasla.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Bundle
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional user-enabled bridge for real TV interaction.
 * Android requires the TV owner to enable this service explicitly in system settings.
 */
class WaslaAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    @Volatile private var pointerOverlay: PointerOverlay? = null
    @Volatile private var pointerWindowManager: WindowManager? = null

    override fun onServiceConnected() {
        instance = this
        try {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            val overlay = PointerOverlay(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            manager.addView(overlay, params)
            pointerWindowManager = manager
            pointerOverlay = overlay
        } catch (_: Exception) {
            pointerOverlay = null
            pointerWindowManager = null
        }
    }

    override fun onDestroy() {
        try { pointerOverlay?.let { pointerWindowManager?.removeView(it) } } catch (_: Exception) { }
        pointerOverlay = null
        pointerWindowManager = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private class PointerOverlay(context: android.content.Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 205, 255); style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3.5f
        }
        @Volatile private var pointerX = 80f
        @Volatile private var pointerY = 80f
        @Volatile private var visible = false

        fun moveTo(x: Float, y: Float) {
            pointerX = x.coerceIn(0f, width.coerceAtLeast(1).toFloat())
            pointerY = y.coerceIn(0f, height.coerceAtLeast(1).toFloat())
            visible = true
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!visible) return
            canvas.drawCircle(pointerX, pointerY, 13f, fill)
            canvas.drawCircle(pointerX, pointerY, 17f, outline)
        }
    }

    fun movePointer(x: Float, y: Float): Boolean {
        val overlay = pointerOverlay ?: return false
        overlay.post { overlay.moveTo(x, y) }
        return true
    }

    fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Float, y: Float, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(500, 2000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(80, 2000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun setFocusedText(value: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun key(name: String): Boolean = when (name.uppercase()) {
        "DPAD_UP" -> moveFocus(View.FOCUS_UP)
        "DPAD_DOWN" -> moveFocus(View.FOCUS_DOWN)
        "DPAD_LEFT" -> moveFocus(View.FOCUS_LEFT)
        "DPAD_RIGHT" -> moveFocus(View.FOCUS_RIGHT)
        "SELECT" -> selectFocused()
        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "VOLUME_UP" -> adjustVolume(AudioManager.ADJUST_RAISE)
        "VOLUME_DOWN" -> adjustVolume(AudioManager.ADJUST_LOWER)
        "MUTE" -> if (Build.VERSION.SDK_INT >= 23) adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE) else false
        else -> false
    }

    private fun moveFocus(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root
        val next = current.focusSearch(direction) ?: return false
        return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }

    private fun selectFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun adjustVolume(direction: Int): Boolean {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    companion object {
        @Volatile private var instance: WaslaAccessibilityService? = null
        fun isEnabled(): Boolean = instance != null
        fun tap(x: Float, y: Float): Boolean = instance?.tap(x, y) ?: false
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean =
            instance?.swipe(x1, y1, x2, y2, duration) ?: false
        fun longPress(x: Float, y: Float, duration: Long): Boolean =
            instance?.longPress(x, y, duration) ?: false
        fun setText(value: String): Boolean = instance?.setFocusedText(value) ?: false
        fun movePointer(x: Float, y: Float): Boolean = instance?.movePointer(x, y) ?: false
        fun key(name: String): Boolean = instance?.key(name) ?: false
    }
}

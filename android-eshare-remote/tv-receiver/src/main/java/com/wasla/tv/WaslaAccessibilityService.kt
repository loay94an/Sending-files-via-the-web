package com.wasla.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.AudioManager
import android.os.Bundle
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional user-enabled bridge for real TV interaction.
 * Android requires the TV owner to enable this service explicitly in system settings.
 */
class WaslaAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Float, y: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(500, 2000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
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
        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "VOLUME_UP" -> adjustVolume(AudioManager.ADJUST_RAISE)
        "VOLUME_DOWN" -> adjustVolume(AudioManager.ADJUST_LOWER)
        "MUTE" -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
        else -> false
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
        fun key(name: String): Boolean = instance?.key(name) ?: false
    }
}

package com.wasla.eshareremote

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer

/** Receives the TV's native WebRTC video track over the already paired PeerJS signaling link. */
class ScreenViewerActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var renderer: SurfaceViewRenderer
    private var peerWebView: WebView? = null
    private var receiver: WaslaWebRtcScreenReceiver? = null
    private var deviceId = ""
    private var pairingCode = ""
    @Volatile private var peerReady = false
    private val retryHandler = Handler(Looper.getMainLooper())
    private var screenRetry: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("remote", MODE_PRIVATE)
        deviceId = prefs.getString("wasla_tv_id", "").orEmpty()
        pairingCode = prefs.getString("wasla_tv_code", "").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xff050b14.toInt())
            setPadding(12, 12, 12, 12)
        }
        status = TextView(this).apply {
            text = "مشاهدة شاشة التلفاز عبر WebRTC"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xffeaf4ff.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(46)))
        renderer = SurfaceViewRenderer(this)
        renderer.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(renderer, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this@ScreenViewerActivity).apply {
            text = "إعادة الاتصال"
            isAllCaps = false
            setOnClickListener { restartConnection() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(Button(this@ScreenViewerActivity).apply {
            text = "إغلاق"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))
        setContentView(root)

        if (!deviceId.matches(Regex("[0-9a-fA-F-]{36}")) || !pairingCode.matches(Regex("\\d{6}"))) {
            status.text = "امسح رمز اقتران وصلة من التلفاز أولاً"
            return
        }
        startReceiver()
        startPeerTransport()
    }

    private fun startReceiver() {
        receiver?.stop()
        receiver = WaslaWebRtcScreenReceiver(
            this, renderer,
            sendSignal = { signal -> sendScreenSignal(signal) },
            onState = { value -> runOnUiThread { updateState(value) } }
        )
    }

    private fun startPeerTransport() {
        peerReady = false
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(ScreenPeerBridge(), "WaslaNative")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val peerId = "wasla-tv-" + deviceId.replace("-", "").lowercase()
                    val options = JSONObject().put("role", "mobile")
                        .put("deviceId", deviceId).put("pin", pairingCode).put("peerId", peerId)
                    view.evaluateJavascript("window.WaslaPeer && window.WaslaPeer.start($options)", null)
                }
            }
        }
        peerWebView = web
        addContentView(web, LinearLayout.LayoutParams(dp(1), dp(1)))
        web.loadUrl("file:///android_asset/peerjs-transport.html")
        status.text = "جارٍ الاتصال بالتلفاز عبر الإنترنت..."
    }

    private fun sendScreenSignal(signal: JSONObject) {
        val quoted = JSONObject.quote(signal.toString())
        runOnUiThread {
            peerWebView?.evaluateJavascript(
                "window.WaslaPeer && window.WaslaPeer.sendScreenSignal($quoted)", null
            )
        }
    }

    private fun restartConnection() {
        status.text = "جارٍ إعادة الاتصال ببث الشاشة..."
        peerReady = false
        receiver?.stop()
        startReceiver()
        peerWebView?.evaluateJavascript("window.WaslaPeer && window.WaslaPeer.stop()", null)
        peerWebView?.let {
            try { (it.parent as? android.view.ViewGroup)?.removeView(it) } catch (_: Exception) {}
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
        }
        peerWebView = null
        startPeerTransport()
    }

    private fun updateState(value: String) {
        status.text = when (value) {
            "connected" -> "تم الاتصال بالتلفاز — جارٍ استقبال الفيديو..."
            "screen-capturing" -> "التلفاز يلتقط الشاشة — جارٍ تجهيز البث..."
            "screen-negotiating", "screen-connecting" -> "جارٍ إنشاء اتصال الفيديو المباشر..."
            "screen-video-track" -> "وصل مسار الفيديو — جارٍ عرض الشاشة..."
            "screen-connected" -> {
                screenRetry?.let { retryHandler.removeCallbacks(it) }
                screenRetry = null
                "متصل — بث الشاشة مباشر عبر WebRTC"
            }
            "screen-disconnected" -> {
                scheduleScreenRetry()
                "انقطع الفيديو — جارٍ استعادة المسار..." 
            }
            "screen-connection-failed" -> {
                scheduleScreenRetry()
                "تعذر إنشاء مسار الفيديو — جارٍ إعادة المحاولة..." 
            }
            "disconnected", "connection-error", "signaling-error" -> "انقطع اتصال الإشارة — تحقق من الإنترنت"
            "authentication-failed" -> "فشل التحقق من اقتران التلفاز"
            "library-unavailable" -> "تعذر تحميل مكتبة الاتصال — تحقق من الإنترنت"
            else -> "حالة بث الشاشة: $value"
        }
    }

    private fun scheduleScreenRetry() {
        if (!peerReady || isFinishing || isDestroyed) return
        screenRetry?.let { retryHandler.removeCallbacks(it) }
        val retry = Runnable {
            screenRetry = null
            if (peerReady && !isFinishing && !isDestroyed) {
                sendScreenSignal(JSONObject().put("type", "request"))
            }
        }
        screenRetry = retry
        retryHandler.postDelayed(retry, 3000L)
    }

    inner class ScreenPeerBridge {
        @JavascriptInterface fun onPeerId(id: String) {}
        @JavascriptInterface fun onPeerStatus(value: String) {
            runOnUiThread { updateState(value) }
        }
        @JavascriptInterface fun onPeerReady(controlReady: Boolean) {
            peerReady = true
            runOnUiThread { status.text = "تم توثيق الاتصال — طلب بث شاشة التلفاز..." }
            sendScreenSignal(JSONObject().put("type", "request"))
        }
        @JavascriptInterface fun onPeerScreenSignal(payload: String) {
            try { receiver?.onSignal(JSONObject(payload)) } catch (_: Exception) {}
        }
        @JavascriptInterface fun onPeerActionResult(id: String, result: String) {}
        @JavascriptInterface fun onPeerError(value: String) {
            runOnUiThread { status.text = "تعذر الاتصال بالتلفاز ($value)" }
        }
        @JavascriptInterface fun isControlEnabled(): Boolean = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        screenRetry?.let { retryHandler.removeCallbacks(it) }
        screenRetry = null
        if (peerReady) {
            peerWebView?.evaluateJavascript(
                "window.WaslaPeer && window.WaslaPeer.sendScreenSignal({type:'stop'})", null
            )
        }
        receiver?.stop()
        receiver = null
        peerWebView?.let {
            try { it.evaluateJavascript("window.WaslaPeer && window.WaslaPeer.stop()", null) } catch (_: Exception) {}
            try { (it.parent as? android.view.ViewGroup)?.removeView(it) } catch (_: Exception) {}
            try { it.stopLoading(); it.destroy() } catch (_: Exception) {}
        }
        peerWebView = null
        super.onDestroy()
    }
}

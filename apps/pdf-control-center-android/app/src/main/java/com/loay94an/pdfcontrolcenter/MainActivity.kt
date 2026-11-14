package com.loay94an.pdfcontrolcenter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = true
        web.addJavascriptInterface(AppBridge(), "NativeApp")
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        setContentView(web)
        val page = assets.open("index.html").bufferedReader().use { it.readText() }
        web.loadDataWithBaseURL("http://localhost/", page, "text/html", "UTF-8", null)
        handleIncoming(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleIncoming(intent)
    }
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!shared.isNullOrBlank()) {
            val encoded = org.json.JSONObject.quote(shared)
            web.postDelayed({ web.evaluateJavascript("window.receiveSharedText && window.receiveSharedText($encoded)", null) }, 700)
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "application/pdf") {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                val name = uri.lastPathSegment ?: "shared.pdf"
                val safe = org.json.JSONObject.quote(name)
                web.postDelayed({ web.evaluateJavascript("window.receiveSharedPdf && window.receiveSharedPdf($safe)", null) }, 900)
            }
        }
    }
    inner class AppBridge {
        @JavascriptInterface fun openChrome(url: String) {
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show() }
            }
        }
        @JavascriptInterface fun shareText(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                startActivity(Intent.createChooser(send, "مشاركة النص"))
            }
        }
        @JavascriptInterface fun showMessage(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message.take(160), Toast.LENGTH_SHORT).show() }
        }
    }
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }
}

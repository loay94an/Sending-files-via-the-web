package com.wasla.tv
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.widget.TextView

class TvReceiverActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff071426.toInt()
        window.navigationBarColor = 0xff071426.toInt()
        startServer()
        render()
    }
    private fun startServer() {
        val intent = Intent(this, WaslaLocalServerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(36, 24, 36, 24)
            setBackgroundColor(0xff071426.toInt())
        }
        root.addView(TextView(this).apply {
            text = "⌁ وصلة TV"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(0xfff4f8ff.toInt())
        }, match())
        root.addView(TextView(this).apply {
            text = "مستقبل وصلة — الخادم المحلي"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xff35d5f4.toInt())
        }, match())
        status = TextView(this).apply {
            text = "الخادم المحلي قيد التشغيل"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xff40dfa2.toInt())
        }
        root.addView(status, match())
        address = TextView(this).apply {
            text = "العنوان: ${WaslaLocalServerService.localIpv4()}:${WaslaLocalServerService.PORT}"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(0xfff4f8ff.toInt())
        }
        root.addView(address, match())
        root.addView(TextView(this).apply {
            text = "امسح رمز QR من تطبيق وصلة على الهاتف للاقتران تلقائياً"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xffa8bdd7.toInt())
        }, match())
        val qrPayload = android.net.Uri.Builder()
            .scheme("wasla").authority("pair")
            .appendQueryParameter("ip", WaslaLocalServerService.localIpv4())
            .appendQueryParameter("port", WaslaLocalServerService.PORT.toString())
            .appendQueryParameter("code", WaslaLocalServerService.pairCode(this))
            .appendQueryParameter("id", WaslaLocalServerService.deviceId(this))
            .build().toString()
        val qrBitmap = try {
            val matrix = QRCodeWriter().encode(qrPayload, BarcodeFormat.QR_CODE, 480, 480)
            Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).also { bitmap ->
                for (x in 0 until 480) for (y in 0 until 480) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        } catch (_: Exception) { null }
        root.addView(ImageView(this).apply {
            setImageBitmap(qrBitmap)
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "رمز QR لاقتران الهاتف بالتلفاز"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        root.addView(TextView(this).apply {
            text = "أو أدخل الرمز يدوياً: " + WaslaLocalServerService.pairCode(this@TvReceiverActivity)
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xffffd166.toInt())
        }, match())
        root.addView(TextView(this).apply {
            text = WaslaLocalServerService.pairCode(this@TvReceiverActivity)
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(0xffffd166.toInt())
        }, match())
        root.addView(TextView(this).apply {
            text = "هذه النسخة تؤسس هوية التلفاز وخادم TCP محلياً. الاتصال عبر الإنترنت ومشاهدة الشاشة والبث والتحكم بالنظام مراحل تالية تتطلب ربط تطبيق الهاتف وخدمة وسيطة وصلاحيات Android."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xffa8bdd7.toInt())
            setPadding(0, 18, 0, 18)
        }, match())
        root.addView(Button(this).apply {
            text = "إعادة تشغيل خادم وصلة"
            isAllCaps = false
            setOnClickListener { startServer(); status.text = "تم طلب تشغيل الخدمة" }
        }, match())
        setContentView(root)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 }
}

package com.wasla.eshareremote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class TvNetworkSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff102a43.toInt()
        window.navigationBarColor = 0xff102a43.toInt()
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(0xfff3f6fa.toInt())
        }

        root.addView(TextView(this).apply {
            text = "وصلة — إعداد شبكة التلفاز"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xff102a43.toInt())
            setPadding(0, 0, 0, dp(12))
        }, match())

        root.addView(TextView(this).apply {
            text = "هذه الشاشة مخصّصة عند تشغيل وصلة على التلفاز نفسه. اضبط الشبكة من إعدادات Android TV حتى يحفظ النظام التغيير بالطريقة الرسمية."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xff334e68.toInt())
            setPadding(0, 0, 0, dp(16))
        }, match())

        root.addView(TextView(this).apply {
            text = "إعدادات IP المطلوبة"
            textSize = 20f
            setTextColor(0xff102a43.toInt())
            setPadding(0, dp(6), 0, dp(8))
        }, match())

        addValue(root, "عنوان IP", "10.157.171.193")
        addValue(root, "طول البادئة", "24")
        addValue(root, "البوابة", "10.157.171.180")
        addValue(root, "DNS", "10.157.171.180")

        root.addView(TextView(this).apply {
            text = "الخطوات: افتح إعدادات Wi‑Fi، اختر الشبكة المتصلة Mohammad، ثم خيارات متقدمة، وبعدها إعدادات IP ← ثابت (Static). أدخل القيم أعلاه واحفظ."
            textSize = 15f
            setTextColor(0xff486581.toInt())
            setPadding(0, dp(12), 0, dp(12))
        }, match())

        root.addView(Button(this).apply {
            text = "فتح إعدادات Wi‑Fi"
            isAllCaps = false
            textSize = 18f
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }, match())

        root.addView(Button(this).apply {
            text = "فحص Root وحفظ نسخة احتياطية"
            isAllCaps = false
            setOnClickListener { checkRootAndBackup() }
        }, match())

        status = TextView(this).apply {
            text = "لم يتم إجراء أي تغيير على الشبكة."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xff486581.toInt())
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(status, match())

        root.addView(Button(this).apply {
            text = "العودة"
            isAllCaps = false
            setOnClickListener { finish() }
        }, match())

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun addValue(root: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(TextView(this).apply {
            text = "$label: $value"
            textSize = 17f
            setTextColor(0xff102a43.toInt())
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(Button(this).apply {
            text = "نسخ"
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                status.text = "تم نسخ $label"
            }
        })
        root.addView(row, match())
    }

    private fun checkRootAndBackup() {
        status.text = "بانتظار صلاحية Root..."
        worker.execute {
            val script = "p=/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml; " +
                "if [ -f \"\$p\" ]; then cp \"\$p\" \"\$p.wasla.bak\" && echo BACKUP_OK; " +
                "else echo CONFIG_NOT_FOUND; exit 2; fi"
            try {
                val process = ProcessBuilder("su", "0", "sh", "-c", script)
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val code = process.waitFor()
                runOnUiThread {
                    status.text = when {
                        code == 0 && output.contains("BACKUP_OK") ->
                            "تم منح Root وإنشاء نسخة احتياطية لإعداد Wi‑Fi. لم يتم تغيير IP."
                        output.contains("CONFIG_NOT_FOUND") ->
                            "صلاحية Root تعمل، لكن ملف إعداد Wi‑Fi غير موجود في المسار المتوقع."
                        else -> "لم تكتمل العملية (رمز $code): $output"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "تعذر طلب Root: ${e.message ?: "غير معروف"}" }
            }
        }
    }

    private fun match() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}

package com.wasla.tv
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.EditText
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

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
            text = "امسح أحد الرمزين: الأول لربط الهاتف بالتلفاز، والثاني لتنزيل تطبيق وصلة للهاتف"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xffa8bdd7.toInt())
        }, match())
        val pairPayload = Uri.Builder()
            .scheme("wasla").authority("pair")
            .appendQueryParameter("transport", "peerjs")
            .appendQueryParameter("peer", "wasla-tv-" + WaslaLocalServerService.deviceId(this).replace("-", "").lowercase())
            .appendQueryParameter("ip", WaslaLocalServerService.localIpv4())
            .appendQueryParameter("port", WaslaLocalServerService.PORT.toString())
            .appendQueryParameter("code", WaslaLocalServerService.pairCode(this))
            .appendQueryParameter("id", WaslaLocalServerService.deviceId(this))
            .build().toString()
        val mobileDownloadUrl = "https://github.com/loay94an/wasla/releases/latest"
        val qrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        fun qrColumn(label: String, payload: String, description: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(this@TvReceiverActivity).apply {
                    text = label
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(0xfff4f8ff.toInt())
                }, LinearLayout.LayoutParams(-1, dp(32)))
                addView(createQrImage(payload, description, 190),
                    LinearLayout.LayoutParams(dp(190), dp(190)))
            }
        }
        qrRow.addView(qrColumn("① اتصال الهاتف", pairPayload, "رمز اقتران وصلة بالتلفاز"),
            LinearLayout.LayoutParams(dp(205), -2).apply { marginEnd = dp(8) })
        qrRow.addView(qrColumn("② تنزيل تطبيق الهاتف", mobileDownloadUrl, "رمز تنزيل تطبيق وصلة للهاتف"),
            LinearLayout.LayoutParams(dp(205), -2).apply { marginStart = dp(8) })
        root.addView(qrRow, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(8)
        })
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
            text = "امسح رمز اتصال الهاتف مرة واحدة لحفظ الاقتران. يستخدم وصلة PeerJS المجاني لتبادل إشارات الاتصال فقط، ثم تنتقل أوامر التحكم مباشرة ومشفرة بين الهاتف والتلفاز عبر WebRTC. لا تحتاج إلى حساب أو إعداد خادم أو فتح منافذ. يلزم اتصال الجهازين بالإنترنت، وقد تمنع بعض أنواع الشبكات الاتصال المباشر. يعمل الاتصال المحلي أيضاً عند توفره. يمكن تشغيل بث الشاشة المحلي MJPEG بعد موافقتك من نافذة أندرويد؛ البث لا يحتاج خادمًا مدفوعًا."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xffa8bdd7.toInt())
            setPadding(0, 18, 0, 18)
        }, match())
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(Button(this).apply {
            text = "إعادة تشغيل الخادم"
            isAllCaps = false
            setOnClickListener { startServer(); status.text = "تم طلب تشغيل الخدمة" }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        actions.addView(Button(this).apply {
            text = "تحديث التطبيق"
            isAllCaps = false
            setOnClickListener { checkForUpdates() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        root.addView(actions, match())
        root.addView(Button(this).apply {
            text = "بدء بث شاشة التلفاز إلى الهاتف (WebRTC)"
            isAllCaps = false
            setOnClickListener {
                try {
                    val manager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), 38473)
                } catch (_: Exception) {
                    status.text = "تعذر بدء التقاط الشاشة على هذا الجهاز"
                }
            }
        }, match())
        root.addView(Button(this).apply {
            text = "بدء بث محلي للشاشة (MJPEG)"
            isAllCaps = false
            setOnClickListener {
                try {
                    val manager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), 38474)
                } catch (_: Exception) {
                    status.text = "تعذر بدء التقاط الشاشة المحلية"
                }
            }
        }, match())
        root.addView(Button(this).apply {
            text = "إيقاف بث الشاشة"
            isAllCaps = false
            setOnClickListener {
                startService(Intent(this@TvReceiverActivity, WaslaLocalServerService::class.java).setAction(WaslaLocalServerService.ACTION_STOP_SCREEN))
                status.text = "تم إيقاف بث الشاشة"
            }
        }, match())
        root.addView(Button(this).apply {
            text = "اختيار مجلد مشاركة الملفات"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 38472)
                } catch (_: Exception) {
                    status.text = "تعذر فتح اختيار المجلدات على هذا الجهاز"
                }
            }
        }, match())
        val sharedFolder = getSharedPreferences("wasla_tv_identity", MODE_PRIVATE).getString("shared_tree_uri", null)
        root.addView(TextView(this).apply {
            text = if (sharedFolder.isNullOrBlank()) "لم يتم اختيار مجلد للملفات بعد" else "مجلد مشاركة الملفات مهيأ"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xffa8bdd7.toInt())
        }, match())
        root.addView(Button(this).apply {
            text = if (WaslaAccessibilityService.isEnabled()) "التحكم الفعلي مفعّل" else "تفعيل التحكم الفعلي بالتلفاز"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                    status.text = "افتح إعدادات إمكانية الوصول يدوياً لتفعيل وصلة"
                }
            }
        }, match())
        root.addView(TextView(this).apply {
            text = "يتطلب التحكم الفعلي تفعيل خدمة وصلة من إعدادات إمكانية الوصول. يمكنك إيقافها في أي وقت."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xffffd166.toInt())
        }, match())
        setContentView(root)
    }
    @Deprecated("Deprecated in Android API; retained for minSdk 21 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 38473 || requestCode == 38474) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, WaslaLocalServerService::class.java)
                    .setAction(if (requestCode == 38473) WaslaLocalServerService.ACTION_START_SCREEN else WaslaLocalServerService.ACTION_START_SCREEN_MJPEG)
                    .putExtra(WaslaLocalServerService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(WaslaLocalServerService.EXTRA_PERMISSION_DATA, data)
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                    status.text = if (requestCode == 38473) "تمت الموافقة — جارٍ تجهيز بث الشاشة المباشر عبر WebRTC" else "تمت الموافقة — جارٍ تشغيل بث الشاشة المحلي عبر MJPEG"
                } catch (_: Exception) {
                    status.text = "تعذر تشغيل خدمة بث الشاشة"
                }
            } else {
                status.text = "لم تتم الموافقة على مشاركة الشاشة"
            }
            return
        }
        if (requestCode == 38472 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
                getSharedPreferences("wasla_tv_identity", MODE_PRIVATE).edit()
                    .putString("shared_tree_uri", uri.toString()).apply()
                status.text = "تم إعداد مجلد مشاركة الملفات"
            } catch (_: Exception) {
                status.text = "لم يتم منح صلاحية دائمة للمجلد؛ اختر مجلداً آخر"
            }
        }
    }

    private fun createQrImage(payload: String, description: String, size: Int): ImageView {
        val bitmap = try {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { image ->
                for (x in 0 until size) for (y in 0 until size) {
                    image.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        } catch (_: Exception) { null }
        return ImageView(this).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = description
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun checkForUpdates() {
        status.text = "جارٍ التحقق من تحديث مستقبل وصلة..."
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
                    setRequestProperty("User-Agent", "Wasla-TV-Android")
                }
                try {
                    if (connection.responseCode == 200) {
                        val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        latestVersion = release.optString("tag_name", "").removePrefix("v")
                        val assets = release.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.optJSONObject(i) ?: continue
                                val assetName = asset.optString("name")
                                if (assetName.startsWith("wasla-tv-receiver-") && assetName.endsWith(".apk")) {
                                    latestVersion = assetName.removePrefix("wasla-tv-receiver-")
                                        .removeSuffix(".apk").removePrefix("v")
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
                    .setTitle("تحديث مستقبل وصلة")
                    .setMessage(message)
                    .setPositiveButton(if (hasNew) "تنزيل التحديث" else "فتح صفحة الإصدارات") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))) }
                        catch (_: Exception) { status.text = "تعذر فتح رابط التحديث" }
                    }
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }, "wasla-tv-update-check").start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 }
}

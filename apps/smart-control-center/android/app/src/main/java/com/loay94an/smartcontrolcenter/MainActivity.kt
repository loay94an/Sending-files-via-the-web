package com.loay94an.smartcontrolcenter

import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val sections = listOf("الرئيسية", "ملفات PDF", "OCR", "النماذج والمحادثة", "المتصفح", "GitHub", "الطرفية والمهام", "الإعدادات", "دليل الاستخدام")

class MainActivity : ComponentActivity() {
    companion object {
        private const val ACTION_TERMUX_AUTO_RESULT = "com.loay94an.smartcontrolcenter.TERMUX_AUTO_RESULT"
    }
    private var termuxAutoResult: CompletableDeferred<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        @Suppress("DEPRECATION")
        val incomingUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        setContent { MaterialTheme { SmartControlCenter(initialText = incoming, initialPdfUri = incomingUri) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_TERMUX_AUTO_RESULT) {
            val result = intent.getBundleExtra("result")
            val stdout = result?.getString("stdout").orEmpty()
            val stderr = result?.getString("stderr").orEmpty()
            val exitCode = result?.getInt("exitCode", 1) ?: 1
            termuxAutoResult?.complete(if (exitCode == 0) stdout else "ERROR: $stderr")
        }
    }

    suspend fun autoConnectTermux(): String {
        if (packageManager.getLaunchIntentForPackage("com.termux") == null) error("ثبّت Termux أولًا.")
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED)
            error("اسمح للتطبيق بصلاحية RUN_COMMAND من إعدادات Android، وفعّل allow-external-apps في Termux.")
        val deferred = CompletableDeferred<String>()
        termuxAutoResult = deferred
        val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val resultIntent = Intent(this, MainActivity::class.java).setAction(ACTION_TERMUX_AUTO_RESULT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getActivity(this, requestCode, resultIntent, flags)
        val command = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", "\$HOME/.local/bin/control-center-bridge-connect"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("pendingIntent", pending)
        }
        try {
            startService(command)
            val raw = withTimeout(60000) { deferred.await() }.trim()
            if (raw.startsWith("ERROR:")) error(raw.removePrefix("ERROR:").trim())
            val found = Regex("(?m)^([a-f0-9]{64})$").find(raw)?.groupValues?.get(1)
                ?: error(raw.ifBlank { "لم يرجع Termux رمز الربط. شغّل setup-auto-connect.sh مرة واحدة أولًا." })
            return found
        } catch (error: SecurityException) {
            throw IllegalStateException("رفض Android تشغيل أوامر Termux. تحقق من RUN_COMMAND وallow-external-apps.")
        } finally {
            termuxAutoResult = null
        }
    }
}

@Composable
private fun SmartControlCenter(initialText: String, initialPdfUri: Uri?) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val prefs = remember { context.getSharedPreferences("smart_control_center", MODE_PRIVATE) }
    var section by remember { mutableStateOf("الرئيسية") }
    var bridge by remember { mutableStateOf(prefs.getString("bridge", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765") }
    var token by remember { mutableStateOf(SecureTokenStore.read(prefs)) }
    var modelEndpoint by remember { mutableStateOf(prefs.getString("modelEndpoint", "http://127.0.0.1:8080/v1") ?: "http://127.0.0.1:8080/v1") }
    var githubToken by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("loay94an/Translation-pdf-and-text-extraction-") }
    var branch by remember { mutableStateOf("feature/smart-control-center-app") }
    var ocrLanguages by remember { mutableStateOf("ara+eng") }
    var ocrDpi by remember { mutableStateOf("250") }
    var githubPath by remember { mutableStateOf("exports/extracted-pages.txt") }
    var githubMessage by remember { mutableStateOf("Export extracted text from Smart PDF Control Center") }
    var command by remember { mutableStateOf("pwd && ls -lah") }
    var prompt by remember { mutableStateOf(initialText) }
    var chatHistory by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var selectedModel by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(listOf<String>()) }
    var pdfPath by remember { mutableStateOf("") }
    var extracted by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("لم يتم اختبار الاتصال بعد") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun saveSettings() {
        prefs.edit().putString("bridge", bridge.trim().trimEnd('/'))
            .putString("modelEndpoint", modelEndpoint.trim().trimEnd('/')).apply()
        SecureTokenStore.write(prefs, token.trim())
    }
    suspend fun call(path: String, method: String = "GET", body: String? = null, raw: ByteArray? = null, mime: String = "application/octet-stream"): String =
        withContext(Dispatchers.IO) {
            val connection = (URL(bridge.trim().trimEnd('/') + path).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = method
                connection.connectTimeout = 8000
                connection.readTimeout = 125000
                connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null || raw != null) {
                    connection.doOutput = true
                    if (raw != null) {
                        connection.setRequestProperty("Content-Type", mime)
                        connection.outputStream.use { it.write(raw) }
                    } else {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.use { it.write(body!!.toByteArray(StandardCharsets.UTF_8)) }
                    }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(600)}")
                text
            } finally { connection.disconnect() }
        }
    fun launchTask(action: suspend () -> String) {
        scope.launch {
            busy = true
            output = try { action().also { status = "اكتملت العملية" } }
            catch (e: Exception) { status = "تعذر إكمال العملية"; "خطأ: ${e.message}" }
            busy = false
        }
    }
    suspend fun uploadPdf(uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(bridge.trim().trimEnd('/') + "/api/files/pdf").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            connection.doOutput = true
            connection.setChunkedStreamingMode(64 * 1024)
            connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            connection.setRequestProperty("Content-Type", "application/pdf")
            val source = context.contentResolver.openInputStream(uri)?.buffered() ?: error("تعذر فتح ملف PDF")
            source.use { input ->
                input.mark(8)
                val signature = ByteArray(5)
                val count = input.read(signature)
                require(count == 5 && String(signature) == "%PDF-") { "الملف ليس PDF صالحًا" }
                input.reset()
                connection.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: ${response.take(500)}")
            JSONObject(response)
        } finally { connection.disconnect() }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && token.isNotBlank()) launchTask {
            val result = uploadPdf(uri)
            pdfPath = result.optString("path")
            "تم استيراد PDF بالتدفق المباشر إلى مساحة Termux\nالمسار: $pdfPath"
        }
    }
    LaunchedEffect(initialPdfUri, token) {
        val uri = initialPdfUri
        if (uri != null) launchTask {
            val result = uploadPdf(uri)
            pdfPath = result.optString("path")
            "تم استيراد PDF المستلم بالمشاركة إلى مساحة Termux\nالمسار: $pdfPath"
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("مركز PDF والتحكم الذكي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("مساحة عمل واحدة للملفات والاستخراج والذكاء المحلي والطرفية", style = MaterialTheme.typography.bodySmall)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sections.forEach { item ->
                    FilterChip(selected = section == item, onClick = { section = item }, label = { Text(item) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("الحالة: $status", style = MaterialTheme.typography.bodySmall)
                when (section) {
                    "الرئيسية" -> {
                        Text("لوحة المتابعة", style = MaterialTheme.typography.headlineSmall)
                        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("الجسر المحلي: ${bridge.trim()}")
                            Text("النموذج: ${selectedModel.ifBlank { "لم يتم اختياره" }}")
                            Text("ملف PDF الحالي: ${pdfPath.ifBlank { "لا يوجد ملف" }}")
                            Button(enabled = !busy && token.isNotBlank(), onClick = { launchTask {
                                val health = JSONObject(call("/api/health"))
                                "الاتصال ناجح\nالنظام: ${health.optString("platform")}\nمساحة العمل: ${health.optString("workspace")}"
                            } }) { Text("فحص الاتصال") }
                        } }
                        Text("مسار العمل", style = MaterialTheme.typography.titleMedium)
                        Text("استيراد PDF ← استخراج النص أو OCR ← تحليل بالنموذج المحلي ← مراجعة النتائج ← حفظ أو رفع الملفات.")
                    }
                    "ملفات PDF" -> {
                        Text("إدارة ملفات PDF", style = MaterialTheme.typography.headlineSmall)
                        Button(enabled = !busy && token.isNotBlank(), onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) { Text("استيراد ملف PDF من الجهاز") }
                        Text("المسار في مساحة Termux: ${pdfPath.ifBlank { "لم يتم استيراد ملف بعد" }}")
                        OutlinedButton(enabled = !busy && pdfPath.isNotBlank() && token.isNotBlank(), onClick = { launchTask {
                            val safe = pdfPath.replace("'", "'\\''")
                            val cmd = "pages=\$(pdfinfo '$safe' | awk '/Pages:/ {print \$2}'); for p in \$(seq 1 \"\$pages\"); do echo \"=== PAGE \$p ===\"; pdftotext -f \"\$p\" -l \"\$p\" -layout '$safe' -; done"
                            val result = JSONObject(call("/api/exec", "POST", JSONObject().put("command", cmd).put("cwd", ".").toString()))
                            extracted = result.optString("stdout")
                            "اكتمل استخراج النص. رمز الخروج: ${result.optInt("exitCode")}\n\n${extracted.take(12000)}"
                        } }) { Text("استخراج النص المضمن") }
                        OutlinedTextField(value = extracted, onValueChange = { extracted = it }, label = { Text("النص المستخرج — قابل للتحرير") }, modifier = Modifier.fillMaxWidth(), minLines = 8)
                        OutlinedButton(enabled = extracted.isNotBlank(), onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, extracted)
                            context.startActivity(Intent.createChooser(send, "تصدير النص"))
                        }) { Text("مشاركة / تصدير النص") }
                    }
                    "OCR" -> {
                        Text("إعدادات OCR", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(value = ocrLanguages, onValueChange = { ocrLanguages = it }, label = { Text("لغات Tesseract مثل ara+eng") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = ocrDpi, onValueChange = { ocrDpi = it.filter(Char::isDigit).take(3) }, label = { Text("دقة التحويل DPI (100–600)") }, modifier = Modifier.fillMaxWidth())
                        Text("استخدم Poppler لتحويل الصفحات المصورة إلى صور، ثم Tesseract للتعرف على العربية والإنجليزية.")
                        OutlinedButton(enabled = !busy && token.isNotBlank() && pdfPath.isNotBlank(), onClick = { launchTask {
                            val safe = pdfPath.replace("'", "'\\''")
                            val dpi = ocrDpi.toIntOrNull()?.takeIf { it in 100..600 } ?: error("قيمة DPI يجب أن تكون بين 100 و600")
                            require(ocrLanguages.matches(Regex("[A-Za-z0-9_+.-]{2,80}"))) { "رمز لغة OCR غير صالح" }
                            val cmd = "mkdir -p ocr exports && pdftoppm -r $dpi -png '$safe' ocr/page && for f in ocr/page-*.png; do [ -f \"\$f\" ] || continue; echo \"--- \$f ---\"; tesseract \"\$f\" stdout -l $ocrLanguages 2>/dev/null; done | tee exports/ocr.txt"
                            val result = JSONObject(call("/api/exec", "POST", JSONObject().put("command", cmd).put("cwd", ".").toString()))
                            result.optString("stdout") + "\n" + result.optString("stderr")
                        } }) { Text("تشغيل OCR عربي + إنجليزي") }
                        Text("إعدادات مقترحة: ara+eng، دقة 250 DPI. يمكن تعديل الأمر حسب نوع الكتاب واللغة.")
                    }
                    "النماذج والمحادثة" -> {
                        Text("النماذج والمحادثة", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(value = modelEndpoint, onValueChange = { modelEndpoint = it }, label = { Text("عنوان خادم llama.cpp") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && token.isNotBlank(), onClick = { launchTask {
                            call("/api/model-config", "POST", JSONObject().put("endpoint", modelEndpoint).toString())
                            val list = JSONObject(call("/api/models")).optJSONArray("models") ?: JSONArray()
                            models = (0 until list.length()).map { list.getJSONObject(it).optString("id") }
                            if (selectedModel.isBlank()) selectedModel = models.firstOrNull().orEmpty()
                            "النماذج المتاحة: ${models.joinToString().ifBlank { "لم يعلن الخادم أي نموذج" }}"
                        } }) { Text("فحص الخادم وجلب النماذج") }
                        if (models.isNotEmpty()) {
                            Text("النموذج المختار")
                            models.forEach { name -> Row { RadioButton(selected = selectedModel == name, onClick = { selectedModel = name }); Text(name, Modifier.padding(top = 12.dp)) } }
                        }
                        chatHistory.forEach { (role, message) ->
                            Text(if (role == "user") "أنت" else "النموذج المحلي", fontWeight = FontWeight.Bold)
                            Text(message)
                        }
                        OutlinedButton(enabled = extracted.isNotBlank(), onClick = { prompt = extracted }) { Text("إرفاق النص المستخرج بالمحادثة") }
                        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("اكتب سؤالك أو ألصق النص للتحليل") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
                        Button(enabled = !busy && token.isNotBlank() && prompt.isNotBlank() && selectedModel.isNotBlank(), onClick = { launchTask {
                            val messages = JSONArray()
                            (chatHistory.takeLast(18) + listOf("user" to prompt)).forEach { (role, message) ->
                                messages.put(JSONObject().put("role", role).put("content", message))
                            }
                            val result = JSONObject(call("/api/chat", "POST", JSONObject().put("provider", "local").put("model", selectedModel).put("messages", messages).toString()))
                            val answer = result.optString("content")
                            chatHistory = chatHistory + listOf("user" to prompt, "assistant" to answer)
                            prompt = ""
                            answer
                        } }) { Text("إرسال إلى النموذج المحلي") }
                        OutlinedButton(enabled = chatHistory.isNotEmpty(), onClick = { chatHistory = emptyList() }) { Text("مسح المحادثة") }
                    }
                    "المتصفح" -> {
                        Text("المتصفح", style = MaterialTheme.typography.headlineSmall)
                        var webUrl by remember { mutableStateOf("https://www.google.com") }
                        OutlinedTextField(value = webUrl, onValueChange = { webUrl = it }, label = { Text("الرابط") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val value = if (webUrl.startsWith("http")) webUrl else "https://$webUrl"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).setPackage("com.android.chrome").let {
                                if (it.resolveActivity(context.packageManager) != null) it else Intent(Intent.ACTION_VIEW, Uri.parse(value))
                            })
                        }) { Text("فتح في Chrome") }
                        Text("لمشاركة محتوى صفحة Chrome إلى التطبيق: استخدم مشاركة Android ثم اختر مركز PDF الذكي. لا تتم قراءة جلسة Chrome أو ملفات تعريف الارتباط.")
                    }
                    "GitHub" -> {
                        Text("GitHub", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(value = githubToken, onValueChange = { githubToken = it }, label = { Text("رمز GitHub Fine-grained PAT") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("المستودع owner/name") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = branch, onValueChange = { branch = it }, label = { Text("الفرع") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && token.isNotBlank() && githubToken.isNotBlank(), onClick = { launchTask {
                            val result = JSONObject(call("/api/github/connect", "POST", JSONObject().put("token", githubToken).toString()))
                            "تم ربط GitHub بالحساب: ${result.optString("login")}"
                        } }) { Text("ربط GitHub") }
                        OutlinedTextField(value = githubPath, onValueChange = { githubPath = it }, label = { Text("مسار ملف التصدير داخل المستودع") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = githubMessage, onValueChange = { githubMessage = it }, label = { Text("رسالة الالتزام") }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(enabled = !busy && token.isNotBlank() && extracted.isNotBlank() && githubPath.isNotBlank(), onClick = { launchTask {
                            val encoded = Base64.encodeToString(extracted.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                            val payload = JSONObject().put("repo", repo).put("branch", branch).put("path", githubPath)
                                .put("message", githubMessage).put("contentBase64", encoded)
                            val result = JSONObject(call("/api/github/upload", "POST", payload.toString()))
                            "تم رفع الملف النصي إلى GitHub: ${result.optString("path")}\nCommit: ${result.optString("commit")}"
                        } }) { Text("رفع النص المستخرج إلى GitHub") }
                        Text("اربط GitHub أولًا. امنح الرمز صلاحية Contents على المستودع المطلوب فقط. لا ترفع كتبًا محمية أو ملفات خاصة إلى مستودع عام.")
                    }
                    "الطرفية والمهام" -> {
                        Text("طرفية Linux والمهام", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("أمر Linux") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Button(enabled = !busy && token.isNotBlank() && command.isNotBlank(), onClick = { launchTask {
                            val result = JSONObject(call("/api/exec", "POST", JSONObject().put("command", command).put("cwd", ".").toString()))
                            "رمز الخروج: ${result.optInt("exitCode")}\n\nSTDOUT:\n${result.optString("stdout")}\n\nSTDERR:\n${result.optString("stderr")}"
                        } }) { Text("تنفيذ الأمر") }
                        OutlinedButton(enabled = !busy && token.isNotBlank(), onClick = { launchTask {
                            val jobs = JSONObject(call("/api/jobs")).optJSONArray("jobs") ?: JSONArray()
                            (0 until jobs.length()).joinToString("\n") { i ->
                                val j = jobs.getJSONObject(i); "${j.optString("id")} — ${j.optString("status")}"
                            }.ifBlank { "لا توجد مهام مسجلة" }
                        } }) { Text("تحديث قائمة المهام") }
                        Text("الأوامر تعمل بصلاحيات مستخدم Termux. راجع الأمر قبل التنفيذ.")
                    }
                    "الإعدادات" -> {
                        Text("إعدادات الاتصال", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(value = bridge, onValueChange = { bridge = it }, label = { Text("عنوان جسر Termux") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("رمز Bearer") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = modelEndpoint, onValueChange = { modelEndpoint = it }, label = { Text("عنوان النموذج المحلي") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy, onClick = { launchTask {
                            val pairedToken = activity.autoConnectTermux()
                            token = pairedToken
                            saveSettings()
                            val health = JSONObject(call("/api/health"))
                            "تم الربط التلقائي بنجاح: " + health.optString("platform")
                        } }) { Text("ربط Termux تلقائيًا واستخراج الرمز") }
                        Button(onClick = { saveSettings(); status = "تم حفظ الإعدادات محليًا" }) { Text("حفظ الإعدادات") }
                        Button(enabled = !busy && token.isNotBlank(), onClick = { saveSettings(); launchTask {
                            val result = JSONObject(call("/api/health"))
                            "اتصال ناجح: ${result.optString("platform")}"
                        } }) { Text("اختبار الجسر") }
                    }
                    "دليل الاستخدام" -> {
                        Text("دليل الاستخدام", style = MaterialTheme.typography.headlineSmall)
                        listOf(
                            "1. ثبّت Termux وثبّت Node.js وPython وPoppler وTesseract.",
                            "2. شغّل mobile-agent في Termux على المنفذ 8765 واحتفظ برمز Bearer.",
                            "3. أدخل عنوان الجسر والرمز في الإعدادات واختبر الاتصال.",
                            "4. شغّل llama.cpp بخادم متوافق مع OpenAI على المنفذ 8080.",
                            "5. استورد PDF، ثم استخرج النص أو شغّل OCR للصفحات المصورة.",
                            "6. اختر النموذج المحلي وأرسل النص لتحليله.",
                            "7. راجع أوامر الطرفية قبل التنفيذ، واستخدم GitHub بصلاحيات محدودة."
                        ).forEach { Text(it) }
                        Text("مهم: لا يتم تضمين النموذج داخل APK. كما أن تسجيل الدخول الرسمي إلى ChatGPT والتحكم الكامل بواجهة Chrome ليسا جزءًا مكتملًا من هذه النسخة.")
                    }
                }
                if (output.isNotBlank()) {
                    HorizontalDivider()
                    Text("النتيجة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SelectionContainerCompat(output)
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, output)
                        context.startActivity(Intent.createChooser(send, "مشاركة النتيجة"))
                    }) { Text("مشاركة النتيجة") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private object SecureTokenStore {
    private const val alias = "smart_control_center_bridge_token"

    fun read(prefs: android.content.SharedPreferences): String {
        return try {
            val stored = prefs.getString("token_encrypted", null) ?: return ""
            val parts = stored.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun write(prefs: android.content.SharedPreferences, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove("token_encrypted").remove("token").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString("token_encrypted", stored).remove("token").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}

@Composable
private fun SelectionContainerCompat(value: String) {
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

private const val MODE_PRIVATE = 0

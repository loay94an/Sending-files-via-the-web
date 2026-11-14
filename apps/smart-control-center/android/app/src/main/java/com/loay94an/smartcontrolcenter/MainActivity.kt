package com.loay94an.smartcontrolcenter

import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        setContent { MaterialTheme { SmartControlCenter(initialText = incoming) } }
    }
}

@Composable
private fun SmartControlCenter(initialText: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("smart_control_center", MODE_PRIVATE) }
    var section by remember { mutableStateOf("الرئيسية") }
    var bridge by remember { mutableStateOf(prefs.getString("bridge", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765") }
    var token by remember { mutableStateOf(SecureTokenStore.read(prefs)) }
    var modelEndpoint by remember { mutableStateOf(prefs.getString("modelEndpoint", "http://127.0.0.1:8080/v1") ?: "http://127.0.0.1:8080/v1") }
    var githubToken by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("loay94an/Translation-pdf-and-text-extraction-") }
    var branch by remember { mutableStateOf("feature/smart-control-center-app") }
    var command by remember { mutableStateOf("pwd && ls -lah") }
    var prompt by remember { mutableStateOf(initialText) }
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
            .putString("modelEndpoint", modelEndpoint.trim().trimEnd('/')).apply()\n        SecureTokenStore.write(prefs, token.trim())
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
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) launchTask {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("تعذر قراءة الملف") }
            require(bytes.size >= 5 && String(bytes.copyOfRange(0, 5)) == "%PDF-") { "الملف ليس PDF صالحًا" }
            val result = JSONObject(call("/api/files/pdf", "POST", raw = bytes, mime = "application/pdf"))
            pdfPath = result.optString("path")
            "تم استيراد الملف إلى مساحة عمل Termux\nالمسار: $pdfPath\nالحجم: ${bytes.size} bytes"
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
                            val result = JSONObject(call("/api/exec", "POST", JSONObject().put("command", "pdftotext -layout '$safe' -").put("cwd", ".").toString()))
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
                        Text("استخدم Poppler لتحويل الصفحات المصورة إلى صور، ثم Tesseract للتعرف على العربية والإنجليزية.")
                        OutlinedButton(enabled = !busy && token.isNotBlank() && pdfPath.isNotBlank(), onClick = { launchTask {
                            val safe = pdfPath.replace("'", "'\\''")
                            val cmd = "mkdir -p ocr && pdftoppm -r 250 -png '$safe' ocr/page && for f in ocr/page-*.png; do echo '--- '"${'$'}(basename "${'$'}f")"' ---'; tesseract "${'$'}f" stdout -l ara+eng 2>/dev/null; done"
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
                        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("اكتب سؤالك أو ألصق النص للتحليل") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
                        Button(enabled = !busy && token.isNotBlank() && prompt.isNotBlank() && selectedModel.isNotBlank(), onClick = { launchTask {
                            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                            val result = JSONObject(call("/api/chat", "POST", JSONObject().put("provider", "local").put("model", selectedModel).put("messages", messages).toString()))
                            result.optString("content")
                        } }) { Text("إرسال إلى النموذج المحلي") }
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
                        Text("امنح الرمز صلاحية Contents حسب الحاجة وعلى المستودع المطلوب فقط. لا تشارك الرمز ولا تضعه في ملفات المشروع.")
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

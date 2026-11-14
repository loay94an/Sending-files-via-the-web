# مركز PDF والتحكم الذكي — Android

تطبيق Android مستقل بواجهة عربية RTL يجمع إدارة PDF وOCR والمحادثة مع نموذج محلي وطرفية Termux وChrome وGitHub.

## المشروع
- Android: `apps/smart-control-center/android/`
- معرف التطبيق: `com.loay94an.smartcontrolcenter`
- إصدار أولي: `1.0.0`
- يعتمد على جسر Termux الموجود في `apps/control-center/mobile-agent/agent.mjs` (المنفذ الافتراضي 8765).
- يعتمد على خادم نموذج متوافق مع OpenAI، مثل llama.cpp، افتراضيًا `http://127.0.0.1:8080/v1`.

## بناء APK
يتطلب JDK 17 وAndroid SDK 35 وGradle 8.9 أو عبر GitHub Actions:
```bash
cd apps/smart-control-center/android
gradle assembleDebug
```
الناتج: `app/build/outputs/apk/debug/app-debug.apk`.

## الإعداد الأولي
1. ثبّت Termux من مصدر موثوق وحدّث الحزم.
2. شغّل وكيل المشروع الموجود في `apps/control-center/mobile-agent` باستخدام `bash install-termux.sh` ثم `bash start.sh`.
3. في التطبيق أدخل عنوان الجسر ورمز Bearer الظاهر في طرفية Termux.
4. أدخل عنوان خادم llama.cpp المتوافق مع OpenAI، ثم حدّث قائمة النماذج.
5. اختر PDF من الجهاز. تُرسل نسخة إلى الجسر كي تتاح لمحركات Poppler/Tesseract في مساحة العمل.
6. راجع أوامر الطرفية قبل التنفيذ.

## الأقسام
لوحة المتابعة، ملفات PDF، OCR، النماذج والمحادثة، Chrome، GitHub، الطرفية والمهام، الإعدادات، دليل الاستخدام.

## حدود النسخة الأولية
- لا يتم تشغيل نموذج GGUF داخل APK؛ يجب تشغيل خادم llama.cpp منفصلًا.
- قائمة النماذج تعرض ما يعلنه خادم `/v1/models`، ولا تفهرس كل ملفات GGUF على الهاتف بعد.
- أوامر الطرفية تنفذ عبر الجسر وبصلاحيات Termux؛ راجعها قبل التنفيذ.
- لا يقرأ التطبيق جلسات Chrome أو ملفات تعريف الارتباط ولا يستخرج محادثات ChatGPT تلقائيًا.
- يُحفظ رمز الجسر مشفرًا باستخدام AES-GCM ومفتاح داخل Android Keystore.
- يلزم اختبار الاتصال والأداء وOCR على هاتف فعلي قبل اعتماد APK نهائي.

لا تضع رموز GitHub أو رموز الاتصال في المستودع أو لقطات الشاشة أو السجلات العامة.

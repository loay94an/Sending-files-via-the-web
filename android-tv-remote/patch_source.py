from pathlib import Path

root = Path("android-tv-remote/source/app/src/main/java/dev/tvtools/pro")
remote_path = root / "ui/screens/RemoteControlScreen.kt"
s = remote_path.read_text(encoding="utf-8")

def replace_once(old: str, new: str):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence ({count}): {old[:100]!r}")
    s = s.replace(old, new, 1)

# Keep the D-pad arrows tied to their physical side in both LTR and RTL.
replace_once(
    "import androidx.compose.ui.platform.LocalDensity\n",
    "import androidx.compose.ui.platform.LocalDensity\nimport androidx.compose.ui.platform.LocalLayoutDirection\nimport androidx.compose.ui.unit.LayoutDirection\n"
)
replace_once(
    "private fun PremiumDPad(viewModel: RemoteViewModel, onCenter: () -> Unit) {\n    val remote = LocalRemotePalette.current\n",
    "private fun PremiumDPad(viewModel: RemoteViewModel, onCenter: () -> Unit) {\n    val remote = LocalRemotePalette.current\n    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl\n"
)
replace_once(
    """            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Left",
            keyCode = KeyCodes.DPAD_LEFT,
            viewModel = viewModel,
            size = arrowButtonSize,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.md)
""",
    """            icon = Icons.Default.KeyboardArrowLeft,
            contentDescription = "Left",
            keyCode = KeyCodes.DPAD_LEFT,
            viewModel = viewModel,
            size = arrowButtonSize,
            modifier = Modifier
                .align(if (isRtl) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(if (isRtl) PaddingValues(end = Spacing.md) else PaddingValues(start = Spacing.md))
"""
)
replace_once(
    """            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Right",
            keyCode = KeyCodes.DPAD_RIGHT,
            viewModel = viewModel,
            size = arrowButtonSize,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.md)
""",
    """            icon = Icons.Default.KeyboardArrowRight,
            contentDescription = "Right",
            keyCode = KeyCodes.DPAD_RIGHT,
            viewModel = viewModel,
            size = arrowButtonSize,
            modifier = Modifier
                .align(if (isRtl) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(if (isRtl) PaddingValues(start = Spacing.md) else PaddingValues(end = Spacing.md))
"""
)

translations = {
    '"Remote Control"': '"التحكم عن بُعد"', '"D-Pad"': '"الاتجاهات"',
    '"Touchpad"': '"لوحة اللمس"', '"Keyboard"': '"لوحة المفاتيح"',
    '"Macros"': '"اختصارات"', '"New Macro"': '"اختصار جديد"',
    '"Macro name"': '"اسم الاختصار"', '"Start Recording"': '"بدء التسجيل"',
    '"Cancel"': '"إلغاء"', '"Stop"': '"إيقاف"', '"Back"': '"رجوع"',
    '"Home"': '"الرئيسية"', '"Menu"': '"القائمة"', '"Power"': '"الطاقة"',
    '"Vol-"': '"خفض الصوت"', '"Vol+"': '"رفع الصوت"', '"Mute"': '"كتم الصوت"',
    '"Prev"': '"السابق"', '"Play"': '"تشغيل"', '"Pause"': '"إيقاف مؤقت"',
    '"Up"': '"أعلى"', '"Down"': '"أسفل"', '"Left"': '"يسار"',
    '"Right"': '"يمين"', '"REC"': '"تسجيل"',
}
for old, new in translations.items():
    s = s.replace(old, new)
remote_path.write_text(s, encoding="utf-8")

# Arabic labels for the persistent bottom navigation.
main_path = root / "MainActivity.kt"
m = main_path.read_text(encoding="utf-8")
for old, new in {
    'ShellTab("Home",': 'ShellTab("الرئيسية",',
    'ShellTab("Remote",': 'ShellTab("الريموت",',
    'ShellTab("Apps",': 'ShellTab("التطبيقات",',
    'ShellTab("Files",': 'ShellTab("الملفات",',
    'ShellTab("Terminal",': 'ShellTab("الطرفية",',
}.items():
    if old not in m:
        raise SystemExit(f"Missing navigation label: {old}")
    m = m.replace(old, new, 1)
main_path.write_text(m, encoding="utf-8")

# Arabic connection form labels and actions.
connect_path = root / "ui/screens/ConnectScreen.kt"
c = connect_path.read_text(encoding="utf-8")
for old, new in {
    '"TV IP address"': '"عنوان IP للتلفاز"',
    '"Port"': '"المنفذ"',
    '"Disconnect"': '"قطع الاتصال"',
    '"Scanning"': '"جارٍ البحث"',
    '"Rescan"': '"إعادة البحث"',
}.items():
    c = c.replace(old, new)
connect_path.write_text(c, encoding="utf-8")

print("Applied Arabic navigation, connection labels, and RTL-safe D-pad.")

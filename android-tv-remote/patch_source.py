from pathlib import Path

p = Path("android-tv-remote/source/app/src/main/java/dev/tvtools/pro/ui/screens/RemoteControlScreen.kt")
s = p.read_text(encoding="utf-8")

def replace_once(old: str, new: str):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence ({count}): {old[:100]!r}")
    s = s.replace(old, new, 1)

# Make the D-pad's physical left/right positions independent of layout direction.
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
    '"Remote Control"': '"التحكم عن بُعد"',
    '"D-Pad"': '"الاتجاهات"',
    '"Touchpad"': '"لوحة اللمس"',
    '"Keyboard"': '"لوحة المفاتيح"',
    '"Macros"': '"اختصارات"',
    '"New Macro"': '"اختصار جديد"',
    '"Macro name"': '"اسم الاختصار"',
    '"Start Recording"': '"بدء التسجيل"',
    '"Cancel"': '"إلغاء"',
    '"Stop"': '"إيقاف"',
    '"Back"': '"رجوع"',
    '"Home"': '"الرئيسية"',
    '"Menu"': '"القائمة"',
    '"Power"': '"الطاقة"',
    '"Vol-"': '"خفض الصوت"',
    '"Vol+"': '"رفع الصوت"',
    '"Mute"': '"كتم الصوت"',
    '"Prev"': '"السابق"',
    '"Play"': '"تشغيل"',
    '"Pause"': '"إيقاف مؤقت"',
    '"Up"': '"أعلى"',
    '"Down"': '"أسفل"',
    '"Left"': '"يسار"',
    '"Right"': '"يمين"',
    '"REC"': '"تسجيل"',
}
for old, new in translations.items():
    s = s.replace(old, new)

p.write_text(s, encoding="utf-8")
print("Patched Arabic remote labels and direction-safe D-pad.")

package app.mafahim.canvas

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MafahimApp() }
    }
}

@Composable
private fun MafahimApp() {
    val context = LocalContext.current
    val repository = remember { DocumentRepository(context) }
    var document by remember { mutableStateOf(DrawingDocument.empty()) }
    var loaded by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(false) }
    var activeLayerId by remember { mutableStateOf("layer-base") }
    var selectedTool by remember { mutableStateOf(BrushTool.PEN) }
    var selectedColor by remember { mutableIntStateOf(Color(0xFF20242D).toArgb()) }
    var brushWidth by remember { mutableStateOf(5f) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var layersDialog by remember { mutableStateOf(false) }
    val undoStack = remember { mutableStateListOf<DrawingDocument>() }
    val redoStack = remember { mutableStateListOf<DrawingDocument>() }

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { repository.load() }
        if (saved != null) {
            document = saved
            activeLayerId = saved.layers.firstOrNull()?.id ?: "layer-base"
        }
        loaded = true
    }

    LaunchedEffect(document, loaded) {
        if (loaded) {
            delay(350)
            withContext(Dispatchers.IO) { repository.save(document) }
        }
    }

    fun applyDocument(next: DrawingDocument) {
        if (next == document) return
        undoStack.add(document)
        if (undoStack.size > 40) undoStack.removeAt(0)
        redoStack.clear()
        document = next
    }

    fun addStroke(stroke: DrawStroke) {
        if (document.layers.none { it.id == stroke.layerId }) return
        applyDocument(document.copy(strokes = document.strokes + stroke))
    }

    val currentPan by rememberUpdatedState(pan)
    val currentZoom by rememberUpdatedState(zoom)
    val currentColor by rememberUpdatedState(selectedColor)
    val currentWidth by rememberUpdatedState(brushWidth)
    val currentLayer by rememberUpdatedState(activeLayerId)
    val addStrokeNow by rememberUpdatedState<(DrawStroke) -> Unit>({ stroke -> addStroke(stroke) })
    val setPanNow by rememberUpdatedState<(Offset) -> Unit>({ value -> pan = value })

    val palette = listOf(
        0xFF20242D.toInt(), 0xFF265D97.toInt(), 0xFFCC4B4B.toInt(),
        0xFF2D8A66.toInt(), 0xFFE1A33A.toInt(), 0xFF7C59A6.toInt(),
        0xFF111111.toInt(), 0xFF777777.toInt()
    )

    val colorScheme = if (darkMode) {
        darkColorScheme(
            primary = Color(0xFF9AB8F0),
            surface = Color(0xFF171A20),
            background = Color(0xFF101318)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF315C87),
            surface = Color.White,
            background = Color(0xFFF3F5F8)
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("مفاهيم", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("استوديو الرسم", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (undoStack.isNotEmpty()) {
                                    redoStack.add(document)
                                    document = undoStack.removeAt(undoStack.lastIndex)
                                }
                            }, enabled = undoStack.isNotEmpty()) { Text("↶", fontSize = 22.sp) }
                            TextButton(onClick = {
                                if (redoStack.isNotEmpty()) {
                                    undoStack.add(document)
                                    document = redoStack.removeAt(redoStack.lastIndex)
                                }
                            }, enabled = redoStack.isNotEmpty()) { Text("↷", fontSize = 22.sp) }
                            TextButton(onClick = { darkMode = !darkMode }) { Text(if (darkMode) "☀" else "☾") }
                            TextButton(onClick = {
                                runCatching {
                                    val uri = SvgExporter.export(context, document)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/svg+xml"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "تصدير الرسم"))
                                }.onFailure {
                                    Toast.makeText(context, "تعذر تصدير الملف", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("تصدير") }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                            Canvas(
                                modifier = Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp))
                                    .pointerInput(selectedTool) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            if (selectedTool == BrushTool.MOVE) {
                                                var previous = down.position
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!change.pressed) break
                                                    val delta = change.position - previous
                                                    setPanNow(currentPan + delta)
                                                    previous = change.position
                                                    change.consume()
                                                }
                                            } else {
                                                val startPan = currentPan
                                                val startZoom = currentZoom
                                                val points = mutableListOf(
                                                    CanvasGeometry.toWorld(
                                                        CanvasPoint(down.position.x, down.position.y),
                                                        startZoom, startPan.x, startPan.y
                                                    )
                                                )
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!change.pressed) break
                                                    points.add(
                                                        CanvasGeometry.toWorld(
                                                            CanvasPoint(change.position.x, change.position.y),
                                                            startZoom, startPan.x, startPan.y
                                                        )
                                                    )
                                                    change.consume()
                                                }
                                                val tool = selectedTool
                                                val color = if (tool == BrushTool.ERASER) Color.White.toArgb() else currentColor
                                                val opacity = when (tool) {
                                                    BrushTool.MARKER -> 0.28f
                                                    BrushTool.PENCIL -> 0.72f
                                                    else -> 1f
                                                }
                                                addStrokeNow(
                                                    DrawStroke(
                                                        id = UUID.randomUUID().toString(),
                                                        layerId = currentLayer,
                                                        tool = tool,
                                                        color = color,
                                                        width = if (tool == BrushTool.ERASER) currentWidth * 3f else currentWidth,
                                                        opacity = opacity,
                                                        points = points
                                                    )
                                                )
                                            }
                                        }
                                    }
                            ) {
                                drawRect(Color(0xFFFCFCFA))
                                val spacing = 34f * zoom.coerceIn(0.2f, 8f)
                                if (spacing >= 8f) {
                                    var gx = ((pan.x % spacing) + spacing) % spacing
                                    var gy = ((pan.y % spacing) + spacing) % spacing
                                    while (gx < size.width) {
                                        drawLine(Color(0xFFE8E9E8), Offset(gx, 0f), Offset(gx, size.height), 0.7f)
                                        gx += spacing
                                    }
                                    while (gy < size.height) {
                                        drawLine(Color(0xFFE8E9E8), Offset(0f, gy), Offset(size.width, gy), 0.7f)
                                        gy += spacing
                                    }
                                }

                                val visibleLayers = document.layers.filter { it.visible }.associateBy { it.id }
                                document.strokes.forEach { stroke ->
                                    val layer = visibleLayers[stroke.layerId] ?: return@forEach
                                    if (stroke.points.isEmpty()) return@forEach
                                    val color = Color(stroke.color).copy(
                                        alpha = (stroke.opacity * layer.opacity).coerceIn(0f, 1f)
                                    )
                                    val mapped = stroke.points.map { Offset(it.x * zoom + pan.x, it.y * zoom + pan.y) }
                                    if (mapped.size == 1) {
                                        drawCircle(
                                            color,
                                            radius = (stroke.width * zoom / 2f).coerceAtLeast(0.5f),
                                            center = mapped.first()
                                        )
                                    } else {
                                        val path = Path().apply {
                                            moveTo(mapped.first().x, mapped.first().y)
                                            mapped.drop(1).forEach { lineTo(it.x, it.y) }
                                        }
                                        drawPath(
                                            path = path,
                                            color = color,
                                            style = Stroke(
                                                width = (stroke.width * zoom).coerceAtLeast(0.5f),
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
                                color = Color.White.copy(alpha = 0.86f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    " ${((zoom * 100).toInt())}% ",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    fontSize = 11.sp,
                                    color = Color.DarkGray
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                BrushTool.PEN to "قلم",
                                BrushTool.PENCIL to "رصاص",
                                BrushTool.MARKER to "ماركر",
                                BrushTool.ERASER to "ممحاة",
                                BrushTool.MOVE to "تحريك"
                            ).forEach { (tool, label) ->
                                FilterChip(
                                    selected = selectedTool == tool,
                                    onClick = { selectedTool = tool },
                                    label = { Text(label, fontSize = 12.sp) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("اللون", fontSize = 12.sp)
                            Row(
                                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                palette.forEach { argb ->
                                    Box(
                                        modifier = Modifier.size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(argb))
                                            .then(
                                                if (selectedColor == argb) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                else Modifier.border(1.dp, Color.LightGray, CircleShape)
                                            )
                                            .clickable { selectedColor = argb }
                                    )
                                }
                            }
                            Text("${brushWidth.toInt()} px", fontSize = 11.sp)
                        }
                        Slider(
                            value = brushWidth,
                            onValueChange = { brushWidth = it },
                            valueRange = 1f..28f,
                            modifier = Modifier.fillMaxWidth().height(25.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { layersDialog = true }) { Text("الطبقات (${document.layers.size})") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { zoom = (zoom / 1.2f).coerceIn(0.25f, 5f) }) { Text("−") }
                            Text("${(zoom * 100).toInt()}%", fontSize = 11.sp)
                            TextButton(onClick = { zoom = (zoom * 1.2f).coerceIn(0.25f, 5f) }) { Text("+") }
                            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("ملاءمة") }
                        }
                    }
                }
            }
        }
    }

    if (layersDialog) {
        AlertDialog(
            onDismissRequest = { layersDialog = false },
            title = { Text("إدارة الطبقات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    document.layers.forEach { layer ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = activeLayerId == layer.id, onClick = { activeLayerId = layer.id })
                            Text(layer.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Checkbox(
                                checked = layer.visible,
                                onCheckedChange = { visible ->
                                    applyDocument(document.copy(layers = document.layers.map {
                                        if (it.id == layer.id) it.copy(visible = visible) else it
                                    }))
                                }
                            )
                            TextButton(
                                onClick = {
                                    if (document.layers.size > 1) {
                                        val nextLayers = document.layers.filterNot { it.id == layer.id }
                                        applyDocument(document.copy(
                                            layers = nextLayers,
                                            strokes = document.strokes.filterNot { it.layerId == layer.id }
                                        ))
                                        if (activeLayerId == layer.id) activeLayerId = nextLayers.last().id
                                    }
                                },
                                enabled = document.layers.size > 1
                            ) { Text("حذف") }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newLayer = DrawingLayer(UUID.randomUUID().toString(), "طبقة ${document.layers.size + 1}")
                    applyDocument(document.copy(layers = document.layers + newLayer))
                    activeLayerId = newLayer.id
                }) { Text("إضافة طبقة") }
            },
            dismissButton = { TextButton(onClick = { layersDialog = false }) { Text("إغلاق") } }
        )
    }
}

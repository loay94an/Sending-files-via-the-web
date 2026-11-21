package app.mafahim.canvas

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DocumentRepository(context: Context) {
    private val target = File(context.filesDir, "mafahim-document.mfd")
    private val temporary = File(context.filesDir, "mafahim-document.mfd.tmp")

    @Synchronized
    fun save(document: DrawingDocument) {
        target.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeUTF(document.title.take(MAX_TEXT))
            val layers = document.layers.take(MAX_LAYERS)
            output.writeInt(layers.size)
            layers.forEach { layer ->
                output.writeUTF(layer.id.take(MAX_TEXT))
                output.writeUTF(layer.name.take(MAX_TEXT))
                output.writeBoolean(layer.visible)
                output.writeFloat(layer.opacity.coerceIn(0f, 1f))
            }
            val strokes = document.strokes.take(MAX_STROKES)
            output.writeInt(strokes.size)
            strokes.forEach { stroke ->
                output.writeUTF(stroke.id.take(MAX_TEXT))
                output.writeUTF(stroke.layerId.take(MAX_TEXT))
                output.writeInt(stroke.tool.ordinal)
                output.writeInt(stroke.color)
                output.writeFloat(stroke.width.coerceIn(0.5f, 100f))
                output.writeFloat(stroke.opacity.coerceIn(0f, 1f))
                val points = stroke.points.take(MAX_POINTS)
                output.writeInt(points.size)
                points.forEach { point ->
                    output.writeFloat(point.x)
                    output.writeFloat(point.y)
                }
            }
            output.flush()
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    @Synchronized
    fun load(): DrawingDocument? {
        if (!target.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(target))).use { input ->
                require(input.readInt() == MAGIC) { "صيغة ملف غير معروفة" }
                require(input.readInt() == FORMAT_VERSION) { "إصدار ملف غير مدعوم" }
                val title = input.readUTF()
                val layerCount = input.readInt().coerceIn(1, MAX_LAYERS)
                val layers = List(layerCount) {
                    DrawingLayer(
                        id = input.readUTF(),
                        name = input.readUTF(),
                        visible = input.readBoolean(),
                        opacity = input.readFloat().coerceIn(0f, 1f)
                    )
                }
                val strokeCount = input.readInt().coerceIn(0, MAX_STROKES)
                val strokes = List(strokeCount) {
                    val id = input.readUTF()
                    val layerId = input.readUTF()
                    val tool = BrushTool.entries.getOrElse(input.readInt()) { BrushTool.PEN }
                    val color = input.readInt()
                    val width = input.readFloat().coerceIn(0.5f, 100f)
                    val opacity = input.readFloat().coerceIn(0f, 1f)
                    val pointCount = input.readInt().coerceIn(0, MAX_POINTS)
                    val points = List(pointCount) { CanvasPoint(input.readFloat(), input.readFloat()) }
                    DrawStroke(id, layerId, tool, color, width, opacity, points)
                }
                DrawingDocument(title, layers, strokes)
            }
        }.getOrNull()
    }

    companion object {
        private const val MAGIC = 0x4D464D31
        private const val FORMAT_VERSION = 1
        private const val MAX_TEXT = 256
        private const val MAX_LAYERS = 128
        private const val MAX_STROKES = 100_000
        private const val MAX_POINTS = 50_000
    }
}

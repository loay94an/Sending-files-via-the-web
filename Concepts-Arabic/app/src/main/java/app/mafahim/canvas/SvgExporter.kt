package app.mafahim.canvas

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object SvgExporter {
    fun export(context: Context, document: DrawingDocument): Uri {
        val visibleLayers = document.layers.filter { it.visible }
        val svg = buildString {
            appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2400\" height=\"1600\" viewBox=\"0 0 2400 1600\">")
            appendLine("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
            visibleLayers.forEach { layer ->
                document.strokes.filter { it.layerId == layer.id }.forEach { stroke ->
                    if (stroke.points.isNotEmpty() && stroke.tool != BrushTool.MOVE) {
                        val points = stroke.points.joinToString(" ") { "${it.x},${it.y}" }
                        val hex = Integer.toHexString(stroke.color).takeLast(6).padStart(6, '0')
                        val alpha = (stroke.opacity * layer.opacity).coerceIn(0f, 1f)
                        appendLine("<polyline points=\"$points\" fill=\"none\" stroke=\"#$hex\" stroke-width=\"${stroke.width}\" opacity=\"$alpha\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
                    }
                }
            }
            appendLine("</svg>")
        }
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val output = File(directory, "mafahim-${System.currentTimeMillis()}.svg")
        output.writeText(svg, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", output)
    }
}

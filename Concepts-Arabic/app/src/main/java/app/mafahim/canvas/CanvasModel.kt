package app.mafahim.canvas

import kotlin.math.hypot

enum class BrushTool { PEN, PENCIL, MARKER, ERASER, MOVE }

data class CanvasPoint(val x: Float, val y: Float)

data class DrawingLayer(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f
)

data class DrawStroke(
    val id: String,
    val layerId: String,
    val tool: BrushTool,
    val color: Int,
    val width: Float,
    val opacity: Float,
    val points: List<CanvasPoint>
)

data class DrawingDocument(
    val title: String,
    val layers: List<DrawingLayer>,
    val strokes: List<DrawStroke>
) {
    companion object {
        fun empty() = DrawingDocument(
            title = "لوحة جديدة",
            layers = listOf(DrawingLayer("layer-base", "الطبقة الأولى")),
            strokes = emptyList()
        )
    }
}

object CanvasGeometry {
    fun toWorld(screen: CanvasPoint, zoom: Float, panX: Float, panY: Float): CanvasPoint {
        val safeZoom = zoom.coerceIn(0.2f, 8f)
        return CanvasPoint((screen.x - panX) / safeZoom, (screen.y - panY) / safeZoom)
    }

    fun distance(a: CanvasPoint, b: CanvasPoint): Float = hypot(a.x - b.x, a.y - b.y)
}

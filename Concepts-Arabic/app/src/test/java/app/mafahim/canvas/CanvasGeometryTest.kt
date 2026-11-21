package app.mafahim.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasGeometryTest {
    @Test
    fun screenPointConvertsToWorldCoordinates() {
        val result = CanvasGeometry.toWorld(CanvasPoint(210f, 130f), 2f, 10f, 30f)
        assertEquals(100f, result.x, 0.001f)
        assertEquals(50f, result.y, 0.001f)
    }

    @Test
    fun zoomIsClampedBeforeCoordinateConversion() {
        val result = CanvasGeometry.toWorld(CanvasPoint(8f, 8f), 0f, 0f, 0f)
        assertEquals(40f, result.x, 0.001f)
        assertEquals(40f, result.y, 0.001f)
    }

    @Test
    fun distanceUsesEuclideanMetric() {
        assertEquals(5f, CanvasGeometry.distance(CanvasPoint(0f, 0f), CanvasPoint(3f, 4f)), 0.001f)
    }
}

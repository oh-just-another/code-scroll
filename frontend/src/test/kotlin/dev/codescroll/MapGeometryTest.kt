package dev.codescroll

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapGeometryTest {
    /** Whole visible lines [start, end). */
    private fun at(lines: Int, height: Int, fit: Boolean, perLine: Int, start: Int, end: Int, fill: Boolean = false) =
        MapGeometry.create(lines, height, fit, fill, perLine, start, end)

    private fun scrolled(lines: Int, height: Int, fit: Boolean, perLine: Int, top: Double, span: Double) =
        MapGeometry.forScroll(lines, height, fit, false, perLine, top, span)

    @Test
    fun fitMapsDocumentBoundariesAndRoundTrips() {
        val geometry = at(1000, 100, true, 3, 200, 300)
        assertEquals(0.1, geometry.scale)
        assertEquals(0.0, geometry.origin)
        assertEquals(0.0, geometry.y(0.0))
        assertEquals(100.0, geometry.y(1000.0))
        for (line in listOf(0, 1, 99, 500, 999)) assertEquals(line, geometry.line(Math.nextUp(geometry.y(line.toDouble()))))
        assertEquals(0, geometry.line(-100.0))
        assertEquals(999, geometry.line(100.0))
        assertEquals(999, geometry.line(Double.MAX_VALUE))
    }

    @Test
    fun shortFitDocumentsDoNotExceedRequestedPixelScale() {
        val geometry = at(10, 100, true, 2, 0, 10)
        assertEquals(2.0, geometry.scale)
        assertEquals(0.0, geometry.origin)
        assertEquals(20.0, geometry.y(10.0))
        assertEquals(9, geometry.line(99.0))
    }

    @Test
    fun fillStretchesShortDocumentsWithinTheFillCap() {
        val filled = at(10, 100, true, 2, 0, 10, fill = true)
        assertEquals(10.0, filled.scale)
        assertEquals(100.0, filled.y(10.0))
        assertEquals(9, filled.line(99.0))
        assertEquals(MapGeometry.MAX_FILL_SCALE, at(2, 100, true, 2, 0, 2, fill = true).scale)
        assertEquals(0.1, at(1000, 100, true, 2, 0, 10, fill = true).scale)
        // Fill only changes Fit; proportional mode keeps pixels-per-line.
        assertEquals(2.0, at(10, 100, false, 2, 0, 10, fill = true).scale)
    }

    @Test
    fun proportionalFrameTravelsFromTopToBottomWithTheScroll() {
        // 1000 lines at 2 px: a 2000 px map shown through a 400 px panel, 50 visible lines = 100 px frame.
        val top = scrolled(1000, 400, false, 2, 0.0, 50.0)
        assertEquals(0.0, top.origin)
        assertEquals(0.0, top.frameTop(0.0, 50.0))
        assertEquals(100.0, top.frameHeight(50.0))
        val middle = scrolled(1000, 400, false, 2, 475.0, 50.0)
        assertEquals(800.0, middle.origin, 1e-9)
        assertEquals(150.0, middle.frameTop(475.0, 50.0), 1e-9)
        val bottom = scrolled(1000, 400, false, 2, 950.0, 50.0)
        assertEquals(1600.0, bottom.origin, 1e-9)
        assertEquals(300.0, bottom.frameTop(950.0, 50.0), 1e-9)
        for (line in listOf(0, 400, 475, 600, 999)) assertEquals(line, middle.line(middle.y(line.toDouble())))
    }

    @Test
    fun frameIsClampedToTheDocumentAndHasAMinimumHeight() {
        // Short document: the frame covers the document only, not the editor's empty space below it.
        val shortFile = scrolled(9, 600, false, 3, 0.0, 44.0)
        assertEquals(27.0, shortFile.frameHeight(44.0))
        assertEquals(0.0, shortFile.frameTop(0.0, 44.0))
        // Long document in Fit: the frame never collapses below the minimum height.
        val fit = scrolled(26_000, 800, true, 3, 13_000.0, 44.0)
        assertEquals(MapGeometry.MIN_VIEWPORT, fit.frameHeight(44.0))
        assertTrue(fit.frameTop(26_000.0, 44.0) + fit.frameHeight(44.0) <= 800 + 1e-9)
    }

    @Test
    fun originsClampAtDocumentStartAndVisibleEnd() {
        val start = at(1000, 100, false, 2, 0, 10)
        assertEquals(0.0, start.origin)
        assertEquals(0, start.line(0.0))
        val end = at(1000, 100, false, 2, 990, 1000)
        assertEquals(1900.0, end.origin, 1e-9)
        assertEquals(100.0, end.y(1000.0), 1e-9)
        assertEquals(999, end.line(100.0))
        assertEquals(950, end.line(0.0))
        assertEquals(end.origin, at(1000, 100, false, 2, 2000, 2010).origin, 1e-9)
    }

    @Test
    fun extremelyLargeDocumentsAvoidIntegerOverflow() {
        val lines = Int.MAX_VALUE
        val fit = at(lines, 4096, true, 3, lines - 100, lines)
        assertEquals(4096.0, fit.y(lines.toDouble()), 1e-9)
        assertEquals(lines - 1, fit.line(4096.0))
        assertEquals(0, fit.line(-Double.MAX_VALUE))
        val proportional = at(lines, 4096, false, 3, lines - 100, lines)
        assertTrue(proportional.origin.isFinite())
        assertEquals(lines * 3.0 - 4096, proportional.origin, 1e-3)
        assertEquals(4096.0, proportional.y(lines.toDouble()), 1e-3)
        assertEquals(lines - 1, proportional.line(Double.MAX_VALUE))
    }

    @Test
    fun resizeRecomputesScaleAndViewportOrigin() {
        var small = at(1000, 100, true, 3, 400, 450)
        var large = at(1000, 300, true, 3, 400, 450)
        assertEquals(small.y(500.0) * 3, large.y(500.0), 1e-9)
        assertEquals(500, large.line(large.y(500.0)))
        // A 150 px frame does not fit a 100 px panel: its top stays visible instead of failing.
        small = at(1000, 100, false, 3, 400, 450)
        assertEquals(1200.0, small.origin, 1e-9)
        assertEquals(0.0, small.frameTop(400.0, 50.0), 1e-9)
        large = at(1000, 300, false, 3, 400, 450)
        val frameTop = large.frameTop(400.0, 50.0)
        assertTrue(frameTop >= 0 && frameTop + large.frameHeight(50.0) <= 300 + 1e-9)
    }

    @Test
    fun emptyDocumentsAndInvalidHeightsAreClamped() {
        val empty = at(0, 0, true, 2, 0, 0)
        assertEquals(1, empty.lines)
        assertEquals(1, empty.height)
        assertEquals(0, empty.line(100.0))
        assertEquals(0, empty.line(-100.0))
        val large = at(-1, Int.MAX_VALUE, true, 2, 0, 0)
        assertEquals(1, large.lines)
        assertEquals(4096, large.height)
    }
}

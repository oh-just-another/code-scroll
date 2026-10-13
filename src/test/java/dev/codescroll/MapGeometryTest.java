package dev.codescroll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapGeometryTest {
    @Test
    void fitMapsDocumentBoundariesAndRoundTrips() {
        MapGeometry geometry = MapGeometry.create(1000, 100, true, 3, 200, 300);
        assertEquals(0.1, geometry.scale());
        assertEquals(0, geometry.origin());
        assertEquals(0, geometry.y(0));
        assertEquals(100, geometry.y(1000));
        for (int line : new int[]{0, 1, 99, 500, 999}) {
            assertEquals(line, geometry.line(Math.nextUp(geometry.y(line))));
        }
        assertEquals(0, geometry.line(-100));
        assertEquals(999, geometry.line(100));
        assertEquals(999, geometry.line(Double.MAX_VALUE));
    }

    @Test
    void shortFitDocumentsDoNotExceedRequestedPixelScale() {
        MapGeometry geometry = MapGeometry.create(10, 100, true, 2, 0, 10);
        assertEquals(2, geometry.scale());
        assertEquals(0, geometry.origin());
        assertEquals(20, geometry.y(10));
        assertEquals(9, geometry.line(99));
    }

    @Test
    void fillStretchesShortDocumentsWithinTheFillCap() {
        MapGeometry filled = MapGeometry.create(10, 100, true, true, 2, 0, 10);
        assertEquals(10, filled.scale());
        assertEquals(100, filled.y(10));
        assertEquals(9, filled.line(99));
        MapGeometry capped = MapGeometry.create(2, 100, true, true, 2, 0, 2);
        assertEquals(MapGeometry.MAX_FILL_SCALE, capped.scale());
        MapGeometry longDocument = MapGeometry.create(1000, 100, true, true, 2, 0, 10);
        assertEquals(0.1, longDocument.scale());
        // Fill only changes Fit; proportional mode keeps pixels-per-line.
        assertEquals(2, MapGeometry.create(10, 100, false, true, 2, 0, 10).scale());
    }

    @Test
    void proportionalFrameTravelsFromTopToBottomWithTheScroll() {
        // 1000 lines at 2 px: a 2000 px map shown through a 400 px panel, 50 visible lines = 100 px frame.
        MapGeometry top = MapGeometry.forScroll(1000, 400, false, false, 2, 0, 50);
        assertEquals(0, top.origin());
        assertEquals(0, top.frameTop(0, 50));
        assertEquals(100, top.frameHeight(50));
        MapGeometry middle = MapGeometry.forScroll(1000, 400, false, false, 2, 475, 50);
        assertEquals(800, middle.origin(), 1e-9);
        assertEquals(150, middle.frameTop(475, 50), 1e-9);
        MapGeometry bottom = MapGeometry.forScroll(1000, 400, false, false, 2, 950, 50);
        assertEquals(1600, bottom.origin(), 1e-9);
        assertEquals(300, bottom.frameTop(950, 50), 1e-9);
        for (int line : new int[]{0, 400, 475, 600, 999}) assertEquals(line, middle.line(middle.y(line)));
    }

    @Test
    void frameIsClampedToTheDocumentAndHasAMinimumHeight() {
        // Short document: the frame covers the document only, not the editor's empty space below it.
        MapGeometry shortFile = MapGeometry.forScroll(9, 600, false, false, 3, 0, 44);
        assertEquals(27, shortFile.frameHeight(44));
        assertEquals(0, shortFile.frameTop(0, 44));
        // Long document in Fit: the frame never collapses below the minimum height.
        MapGeometry fit = MapGeometry.forScroll(26_000, 800, true, false, 3, 13_000, 44);
        assertEquals(MapGeometry.MIN_VIEWPORT, fit.frameHeight(44));
        assertTrue(fit.frameTop(26_000, 44) + fit.frameHeight(44) <= 800 + 1e-9);
    }
    @Test
    void originsClampAtDocumentStartAndVisibleEnd() {
        MapGeometry start = MapGeometry.create(1000, 100, false, 2, 0, 10);
        assertEquals(0, start.origin());
        assertEquals(0, start.line(0));
        MapGeometry end = MapGeometry.create(1000, 100, false, 2, 990, 1000);
        assertEquals(1900, end.origin(), 1e-9);
        assertEquals(100, end.y(1000), 1e-9);
        assertEquals(999, end.line(100));
        assertEquals(950, end.line(0));
        MapGeometry outside = MapGeometry.create(1000, 100, false, 2, 2000, 2010);
        assertEquals(end.origin(), outside.origin(), 1e-9);
    }
    @Test
    void extremelyLargeDocumentsAvoidIntegerOverflow() {
        int lines = Integer.MAX_VALUE;
        MapGeometry fit = MapGeometry.create(lines, 4096, true, 3, lines - 100, lines);
        assertEquals(4096, fit.y(lines), 1e-9);
        assertEquals(lines - 1, fit.line(4096));
        assertEquals(0, fit.line(-Double.MAX_VALUE));
        MapGeometry proportional = MapGeometry.create(lines, 4096, false, 3, lines - 100, lines);
        assertTrue(Double.isFinite(proportional.origin()));
        assertEquals(lines * 3.0 - 4096, proportional.origin(), 1e-3);
        assertEquals(4096, proportional.y(lines), 1e-3);
        assertEquals(lines - 1, proportional.line(Double.MAX_VALUE));
    }
    @Test
    void resizeRecomputesScaleAndViewportOrigin() {
        MapGeometry small = MapGeometry.create(1000, 100, true, 3, 400, 450);
        MapGeometry large = MapGeometry.create(1000, 300, true, 3, 400, 450);
        assertEquals(small.y(500) * 3, large.y(500), 1e-9);
        assertEquals(500, large.line(large.y(500)));
        // A 150 px frame does not fit a 100 px panel: its top stays visible instead of failing.
        small = MapGeometry.create(1000, 100, false, 3, 400, 450);
        assertEquals(1200, small.origin(), 1e-9);
        assertEquals(0, small.frameTop(400, 50), 1e-9);
        large = MapGeometry.create(1000, 300, false, 3, 400, 450);
        double frameTop = large.frameTop(400, 50);
        assertTrue(frameTop >= 0 && frameTop + large.frameHeight(50) <= 300 + 1e-9);
    }
    @Test
    void emptyDocumentsAndInvalidHeightsAreClamped() {
        MapGeometry empty = MapGeometry.create(0, 0, true, 2, 0, 0);
        assertEquals(1, empty.lines());
        assertEquals(1, empty.height());
        assertEquals(0, empty.line(100));
        assertEquals(0, empty.line(-100));
        MapGeometry large = MapGeometry.create(-1, Integer.MAX_VALUE, true, 2, 0, 0);
        assertEquals(1, large.lines());
        assertEquals(4096, large.height());
    }
}

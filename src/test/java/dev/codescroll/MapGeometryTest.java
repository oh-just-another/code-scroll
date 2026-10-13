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
    void proportionalModeCentersViewportAndRoundTrips() {
        MapGeometry geometry = MapGeometry.create(1000, 100, false, 2, 400, 450);
        assertEquals(2, geometry.scale());
        assertEquals(800, geometry.origin());
        assertEquals(0, geometry.y(400));
        assertEquals(100, geometry.y(450));
        for (int line : new int[]{0, 400, 425, 450, 999}) assertEquals(line, geometry.line(geometry.y(line)));
        assertEquals(425, geometry.line(50));
    }

    @Test
    void originsClampAtDocumentStartAndVisibleEnd() {
        MapGeometry start = MapGeometry.create(1000, 100, false, 2, 0, 10);
        assertEquals(0, start.origin());
        assertEquals(0, start.line(0));
        MapGeometry end = MapGeometry.create(1000, 100, false, 2, 990, 1000);
        assertEquals(1900, end.origin());
        assertEquals(100, end.y(1000));
        assertEquals(999, end.line(100));
        assertEquals(950, end.line(0));
        MapGeometry outside = MapGeometry.create(1000, 100, false, 2, 2000, 3000);
        assertEquals(end.origin(), outside.origin());
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
        assertEquals(lines * 3.0 - 4096, proportional.origin());
        assertEquals(4096, proportional.y(lines));
        assertEquals(lines - 100, proportional.line(proportional.y(lines - 100)));
        assertEquals(lines - 1, proportional.line(Double.MAX_VALUE));
    }

    @Test
    void resizeRecomputesScaleAndViewportOrigin() {
        MapGeometry small = MapGeometry.create(1000, 100, true, 3, 400, 450);
        MapGeometry large = MapGeometry.create(1000, 300, true, 3, 400, 450);
        assertEquals(small.y(500) * 3, large.y(500), 1e-9);
        assertEquals(500, large.line(large.y(500)));
        small = MapGeometry.create(1000, 100, false, 3, 400, 450);
        large = MapGeometry.create(1000, 300, false, 3, 400, 450);
        assertEquals(small.origin() - 100, large.origin());
        assertEquals(425, small.line(50));
        assertEquals(425, large.line(150));
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

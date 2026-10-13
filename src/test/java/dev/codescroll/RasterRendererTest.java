package dev.codescroll;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RasterRendererTest {
    private static final int FG = 0xabcdef;
    private static final int BG = 0x123456;

    @Test
    void emptyFileIsFullyPaintedAndDimensionsAreClamped() {
        BufferedImage image = render("", Integer.MIN_VALUE, Integer.MAX_VALUE,
                new int[0], new int[0], new int[0], List.of(), null);
        assertEquals(40, image.getWidth());
        assertEquals(RasterRenderer.MAX_HEIGHT, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
        for (int pixel : ((DataBufferInt) image.getRaster().getDataBuffer()).getData()) assertEquals(BG, pixel);
        image = render("", Integer.MAX_VALUE, 0, new int[0], new int[0], new int[0], List.of(), null);
        assertEquals(RasterRenderer.MAX_WIDTH, image.getWidth());
        assertEquals(1, image.getHeight());
    }

    @Test
    void syntaxColorsUseDocumentOffsetsAndFallbackInGaps() {
        String text = "ignored\na bcde";
        BufferedImage image = render(text, 40, 1, new int[]{8}, new int[]{text.length()}, new int[]{0},
                List.of(new RasterRenderer.Token(0, 7, 0xff0000),
                        new RasterRenderer.Token(10, 12, 0x00ff00),
                        new RasterRenderer.Token(13, 14, 0x0000ff)), null);
        assertPixel(image, 4, 0, FG);
        assertPixel(image, 5, 0, BG);
        assertPixel(image, 6, 0, 0x00ff00);
        assertPixel(image, 7, 0, 0x00ff00);
        assertPixel(image, 8, 0, FG);
        assertPixel(image, 9, 0, 0x0000ff);
    }

    @Test
    void tabsUnicodeAndReservedMarginsUseSinglePixelCells() {
        String text = "a\tb\t\ud83d\ude00";
        BufferedImage image = render(text, 40, 2, new int[]{0}, new int[]{text.length()}, new int[]{1}, List.of(), null);
        assertPixel(image, 4, 1, FG);
        assertPixel(image, 8, 1, FG);
        assertPixel(image, 12, 1, FG);
        assertPixel(image, 13, 1, BG);
        for (int x = 0; x < 40; x++) assertPixel(image, x, 0, BG);
        for (int x = 34; x < 40; x++) assertPixel(image, x, 1, BG);
    }

    @Test
    void hugeLinesAreBoundedAndClippedBeforeMarkerStrip() {
        AtomicInteger reads = new AtomicInteger();
        CharSequence huge = new CharSequence() {
            public int length() { return Integer.MAX_VALUE; }
            public char charAt(int index) { reads.incrementAndGet(); return 'x'; }
            public CharSequence subSequence(int start, int end) { throw new AssertionError("No copying huge lines"); }
        };
        BufferedImage image = render(huge, 10000, 1, new int[]{0}, new int[]{Integer.MAX_VALUE}, new int[]{0}, List.of(), null);
        assertTrue(reads.get() <= RasterRenderer.MAX_COLUMNS);
        assertPixel(image, 293, 0, FG);
        assertPixel(image, 294, 0, BG);
        BufferedImage spaces = render(" ".repeat(512) + "x", 300, 1,
                new int[]{0}, new int[]{513}, new int[]{0}, List.of(), null);
        assertPixel(spaces, 4, 0, BG);
    }

    @Test
    void atMostThreeSamplesPerRowAndInputsAreNotMutated() {
        int[] starts = {0, 2, 4, 6};
        int[] ends = {1, 3, 5, 7};
        int[] rows = {0, 0, 0, 0};
        List<RasterRenderer.Token> tokens = new ArrayList<>(List.of(new RasterRenderer.Token(6, 7, 0xff0000)));
        BufferedImage image = render("a b c d", 40, 1, starts, ends, rows, tokens, null);
        assertPixel(image, 4, 0, FG);
        assertArrayEquals(new int[]{0, 2, 4, 6}, starts);
        assertArrayEquals(new int[]{1, 3, 5, 7}, ends);
        assertArrayEquals(new int[]{0, 0, 0, 0}, rows);
        assertEquals(List.of(new RasterRenderer.Token(6, 7, 0xff0000)), tokens);
    }

    @Test
    void rejectsMismatchesOffsetsRowsAndUnsortedTokens() {
        assertThrows(IllegalArgumentException.class, () -> render("x", 40, 1, new int[]{0}, new int[0], new int[]{0}, List.of(), null));
        for (int[] range : new int[][]{{-1, 1}, {1, 0}, {0, 2}}) {
            assertThrows(IllegalArgumentException.class, () -> render("x", 40, 1, new int[]{range[0]}, new int[]{range[1]}, new int[]{0}, List.of(), null));
        }
        for (int row : new int[]{-1, 1}) {
            assertThrows(IllegalArgumentException.class, () -> render("x", 40, 1, new int[]{0}, new int[]{1}, new int[]{row}, List.of(), null));
        }
        assertThrows(IllegalArgumentException.class, () -> render("xx", 40, 1, new int[0], new int[0], new int[0],
                List.of(new RasterRenderer.Token(1, 2, FG), new RasterRenderer.Token(0, 1, FG)), null));
        assertThrows(IllegalArgumentException.class, () -> render("x", 40, 1, new int[0], new int[0], new int[0],
                List.of(new RasterRenderer.Token(0, 2, FG)), null));
    }

    @Test
    void cancellationIsCheckedAtSixtyFourEntryIntervalsAndPropagated() {
        AtomicInteger checks = new AtomicInteger();
        RuntimeException canceled = new RuntimeException("canceled");
        assertSame(canceled, assertThrows(RuntimeException.class, () -> render("", 40, 1,
                new int[129], new int[129], new int[129], List.of(), () -> {
                    if (checks.incrementAndGet() == 3) throw canceled;
                })));
        assertEquals(3, checks.get());
    }

    @Test
    void accurateStyleBlendsByGlyphCoverageWhileCleanDrawsSolidStrokes() {
        String text = "W.a";
        int[] starts = {0};
        int[] ends = {3};
        int[] rows = {0};
        BufferedImage accurate = RasterRenderer.render(text, 40, 1, starts, ends, rows, 0xffffff, 0x000000,
                List.of(), true, null);
        assertPixel(accurate, 4, 0, 0xffffff);
        assertPixel(accurate, 5, 0, RasterRenderer.blend(0xffffff, 0, RasterRenderer.coverage('.')));
        assertPixel(accurate, 6, 0, RasterRenderer.blend(0xffffff, 0, RasterRenderer.coverage('a')));
        assertTrue((accurate.getRGB(5, 0) & 0xff) < (accurate.getRGB(6, 0) & 0xff));
        BufferedImage clean = RasterRenderer.render(text, 40, 1, starts, ends, rows, 0xffffff, 0x000000,
                List.of(), false, null);
        for (int x = 4; x <= 6; x++) assertPixel(clean, x, 0, 0xffffff);
    }

    @Test
    void intensityBlendsTextTowardsTheBackground() {
        int[] one = {0};
        BufferedImage half = RasterRenderer.render("W", 40, 1, one, new int[]{1}, one, 0xffffff, 0x000000,
                List.of(), false, 50, null);
        assertPixel(half, 4, 0, RasterRenderer.blend(0xffffff, 0, 128));
        BufferedImage full = RasterRenderer.render("W", 40, 1, one, new int[]{1}, one, 0xffffff, 0x000000,
                List.of(), false, 100, null);
        assertPixel(full, 4, 0, 0xffffff);
    }

    private static BufferedImage render(CharSequence text, int width, int height, int[] starts, int[] ends,
                                         int[] rows, List<RasterRenderer.Token> tokens, Runnable cancel) {
        return RasterRenderer.render(text, width, height, starts, ends, rows, FG, BG, tokens, cancel);
    }

    private static void assertPixel(BufferedImage image, int x, int y, int rgb) {
        assertEquals(rgb, image.getRGB(x, y) & 0xffffff, "pixel " + x + "," + y);
    }
}

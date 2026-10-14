package dev.codescroll

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.roundToInt

class RasterRendererTest {
    private fun render(
        text: CharSequence, width: Int, height: Int, starts: IntArray, ends: IntArray, rows: IntArray,
        tokens: List<RasterRenderer.Token> = emptyList(), cancel: (() -> Unit)? = null,
    ) = RasterRenderer.render(text, width, height, starts, ends, rows, FG, BG, tokens, checkCanceled = cancel)

    private fun assertPixel(image: BufferedImage, x: Int, y: Int, rgb: Int) =
        assertEquals(rgb, image.getRGB(x, y) and 0xffffff, "pixel $x,$y")

    @Test
    fun emptyFileIsFullyPaintedAndDimensionsAreClamped() {
        var image = render("", Int.MIN_VALUE, Int.MAX_VALUE, IntArray(0), IntArray(0), IntArray(0))
        assertEquals(40, image.width)
        assertEquals(RasterRenderer.MAX_HEIGHT, image.height)
        assertEquals(BufferedImage.TYPE_INT_RGB, image.type)
        for (pixel in (image.raster.dataBuffer as DataBufferInt).data) assertEquals(BG, pixel)
        image = render("", Int.MAX_VALUE, 0, IntArray(0), IntArray(0), IntArray(0))
        assertEquals(RasterRenderer.MAX_WIDTH, image.width)
        assertEquals(1, image.height)
    }

    @Test
    fun syntaxColorsUseDocumentOffsetsAndFallbackInGaps() {
        val text = "ignored\na bcde"
        val image = render(text, 40, 1, intArrayOf(8), intArrayOf(text.length), intArrayOf(0), listOf(
            RasterRenderer.Token(0, 7, 0xff0000), RasterRenderer.Token(10, 12, 0x00ff00), RasterRenderer.Token(13, 14, 0x0000ff),
        ))
        assertPixel(image, 4, 0, FG)
        assertPixel(image, 5, 0, BG)
        assertPixel(image, 6, 0, 0x00ff00)
        assertPixel(image, 7, 0, 0x00ff00)
        assertPixel(image, 8, 0, FG)
        assertPixel(image, 9, 0, 0x0000ff)
    }

    @Test
    fun tabsUnicodeAndReservedMarginsUseSinglePixelCells() {
        val text = "a\tb\t😀"
        val image = render(text, 40, 2, intArrayOf(0), intArrayOf(text.length), intArrayOf(1))
        assertPixel(image, 4, 1, FG)
        assertPixel(image, 8, 1, FG)
        assertPixel(image, 12, 1, FG)
        assertPixel(image, 13, 1, BG)
        for (x in 0 until 40) assertPixel(image, x, 0, BG)
        for (x in 34 until 40) assertPixel(image, x, 1, BG)
    }

    @Test
    fun hugeLinesAreBoundedAndClipped() {
        var reads = 0
        val huge = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char {
                reads++
                return 'x'
            }

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("No copying huge lines")
        }
        val image = render(huge, 10_000, 1, intArrayOf(0), intArrayOf(Int.MAX_VALUE), intArrayOf(0))
        assertTrue(reads <= RasterRenderer.MAX_COLUMNS)
        // At most MAX_COLUMNS characters are read and drawn, however wide the raster.
        assertPixel(image, RasterRenderer.INDENT + RasterRenderer.MAX_COLUMNS - 1, 0, FG)
        assertPixel(image, RasterRenderer.INDENT + RasterRenderer.MAX_COLUMNS, 0, BG)
        val spaces = render(" ".repeat(512) + "x", 300, 1, intArrayOf(0), intArrayOf(513), intArrayOf(0))
        assertPixel(spaces, 4, 0, BG)
    }

    @Test
    fun samplesSharingARowAccumulateAndInputsAreNotMutated() {
        val starts = intArrayOf(0, 2, 4, 6)
        val ends = intArrayOf(1, 3, 5, 7)
        val rows = intArrayOf(0, 0, 0, 0)
        val tokens = listOf(RasterRenderer.Token(6, 7, 0xff0000))
        val image = render("a b c d", 40, 1, starts, ends, rows, tokens)
        assertNotEquals(BG, image.getRGB(4, 0) and 0xffffff)
        assertArrayEquals(intArrayOf(0, 2, 4, 6), starts)
        assertArrayEquals(intArrayOf(1, 3, 5, 7), ends)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), rows)
        assertEquals(listOf(RasterRenderer.Token(6, 7, 0xff0000)), tokens)
    }

    @Test
    fun rejectsMismatchesOffsetsRowsAndUnsortedTokens() {
        assertThrows<IllegalArgumentException> { render("x", 40, 1, intArrayOf(0), IntArray(0), intArrayOf(0)) }
        for ((start, end) in listOf(-1 to 1, 1 to 0, 0 to 2)) {
            assertThrows<IllegalArgumentException> { render("x", 40, 1, intArrayOf(start), intArrayOf(end), intArrayOf(0)) }
        }
        for (row in listOf(-1, 1)) {
            assertThrows<IllegalArgumentException> { render("x", 40, 1, intArrayOf(0), intArrayOf(1), intArrayOf(row)) }
        }
        assertThrows<IllegalArgumentException> {
            render("xx", 40, 1, IntArray(0), IntArray(0), IntArray(0),
                listOf(RasterRenderer.Token(1, 2, FG), RasterRenderer.Token(0, 1, FG)))
        }
        assertThrows<IllegalArgumentException> {
            render("x", 40, 1, IntArray(0), IntArray(0), IntArray(0), listOf(RasterRenderer.Token(0, 2, FG)))
        }
    }

    @Test
    fun cancellationIsCheckedAtSixtyFourEntryIntervalsAndPropagated() {
        var checks = 0
        val canceled = RuntimeException("canceled")
        val thrown = assertThrows<RuntimeException> {
            render("", 40, 1, IntArray(129), IntArray(129), IntArray(129)) { if (++checks == 3) throw canceled }
        }
        assertSame(canceled, thrown)
        assertEquals(3, checks)
    }

    @Test
    fun accurateStyleBlendsByGlyphCoverageWhileCleanDrawsSolidStrokes() {
        val text = "W.a"
        val accurate = RasterRenderer.render(text, 40, 1, intArrayOf(0), intArrayOf(3), intArrayOf(0), 0xffffff, 0, emptyList(), accurate = true)
        assertPixel(accurate, 4, 0, RasterRenderer.blend(0xffffff, 0, RasterRenderer.coverage('W')))
        assertPixel(accurate, 5, 0, RasterRenderer.blend(0xffffff, 0, RasterRenderer.coverage('.')))
        assertPixel(accurate, 6, 0, RasterRenderer.blend(0xffffff, 0, RasterRenderer.coverage('a')))
        assertTrue((accurate.getRGB(5, 0) and 0xff) < (accurate.getRGB(6, 0) and 0xff))
        val clean = RasterRenderer.render(text, 40, 1, intArrayOf(0), intArrayOf(3), intArrayOf(0), 0xffffff, 0, emptyList())
        for (x in 4..6) assertPixel(clean, x, 0, 0xffffff)
    }

    @Test
    fun cleanKeepsALightFirstRowAsSpacingBetweenLines() {
        // A line drawn over four rows: the first row is the gap, the rest are solid.
        val image = RasterRenderer.render(
            "x", 40, 4, IntArray(4), IntArray(4) { 1 }, intArrayOf(0, 1, 2, 3), 0xffffff, 0, emptyList(),
            phases = floatArrayOf(0.125f, 0.375f, 0.625f, 0.875f), rowsPerLine = 4.0,
        )
        assertTrue((image.getRGB(4, 0) and 0xff) < 64)
        for (y in 1 until 4) assertPixel(image, 4, y, 0xffffff)
        // Two rows per line are too few to spare one for spacing.
        assertEquals(256, RasterRenderer.weight('x', 0.25f, 2.0, false))
    }

    @Test
    fun accurateShapesGlyphsByTheirUpperAndLowerInk() {
        assumeTrue(RasterRenderer.measured, "no fonts to measure glyphs")
        // Ascenders carry ink in the upper half; descenders in the lower half.
        assertTrue(RasterRenderer.weight('l', 0.3f, 4.0, true) > RasterRenderer.weight('o', 0.3f, 4.0, true))
        assertTrue(RasterRenderer.weight('g', 0.8f, 4.0, true) > RasterRenderer.weight('l', 0.8f, 4.0, true))
        assertTrue(RasterRenderer.weight('.', 0.3f, 4.0, true) < RasterRenderer.weight('M', 0.3f, 4.0, true))
    }

    @Test
    fun hiDpiCellsAreWiderThanOnePixel() {
        val image = RasterRenderer.render("ab", 80, 1, intArrayOf(0), intArrayOf(2), intArrayOf(0), 0xffffff, 0, emptyList(),
            charWidth = 2, indent = 8)
        for (x in 8 until 12) assertPixel(image, x, 0, 0xffffff)
        assertPixel(image, 12, 0, 0)
        assertPixel(image, 7, 0, 0)
    }

    @Test
    fun antialiasingBlendsPartiallyCoveringLinesByCoverage() {
        // Line "x" covers 60% of the row, the empty line the rest: the pixel is 60% ink, not all or nothing.
        val image = RasterRenderer.render("x\n \n", 40, 1, intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(0, 0), 0xffffff, 0,
            emptyList(), coverages = floatArrayOf(0.6f, 0.4f))
        val expected = RasterRenderer.blend(0xffffff, 0, (0.6f * 256).roundToInt()) and 0xff
        assertEquals(expected.toDouble(), (image.getRGB(4, 0) and 0xff).toDouble(), 1.0)
    }

    @Test
    fun antialiasingAveragesColorsOfLinesSharingARow() {
        val tokens = listOf(RasterRenderer.Token(0, 1, 0xff0000), RasterRenderer.Token(2, 3, 0x0000ff))
        val image = RasterRenderer.render("a\nb\n", 40, 1, intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(0, 0), 0xffffff, 0,
            tokens, coverages = floatArrayOf(0.5f, 0.5f), rowsPerLine = 0.5)
        val rgb = image.getRGB(4, 0) and 0xffffff
        // Both lines contribute: neither pure red nor pure blue.
        assertTrue(((rgb shr 16) and 0xff) > 100 && (rgb and 0xff) > 100, Integer.toHexString(rgb))
    }

    @Test
    fun intensityBlendsTextTowardsTheBackground() {
        val half = RasterRenderer.render("W", 40, 1, intArrayOf(0), intArrayOf(1), intArrayOf(0), 0xffffff, 0, emptyList(), intensity = 50)
        assertPixel(half, 4, 0, RasterRenderer.blend(0xffffff, 0, 128))
        val full = RasterRenderer.render("W", 40, 1, intArrayOf(0), intArrayOf(1), intArrayOf(0), 0xffffff, 0, emptyList())
        assertPixel(full, 4, 0, 0xffffff)
    }

    private companion object {
        const val FG = 0xabcdef
        const val BG = 0x123456
    }
}

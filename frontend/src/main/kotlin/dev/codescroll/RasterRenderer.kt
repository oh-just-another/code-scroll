package dev.codescroll

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import kotlin.math.roundToInt

/**
 * A bounded, software-only, antialiased raster of sampled document lines.
 *
 * Each sample is one raster row of one document line. Every line sharing a row contributes in proportion to
 * the share of the row it covers ([coverages]), so thin or fractional lines fade smoothly instead of
 * flickering in and out of pixels. The row's position inside its line ([phases]) shapes the stroke: CLEAN keeps
 * a light first row as spacing between lines; ACCURATE also uses each glyph's ink in its upper and lower half,
 * so capitals, ascenders and descenders read differently from lower-case text.
 */
object RasterRenderer {
    /** Raster limits in device pixels. */
    const val MAX_WIDTH = 600
    const val MAX_HEIGHT = 4096
    const val MAX_COLUMNS = 512
    /** Default left indent of the text, in device pixels at 1x. */
    const val INDENT = 4

    data class Token(val start: Int, val end: Int, val rgb: Int)

    /** Per-glyph ink in the upper and lower half, 0..256, measured once from a monospaced font. */
    private val top = IntArray(128) { 180 }
    private val bottom = IntArray(128) { 180 }

    /** Whether glyph shapes were measured; headless systems without fonts render every glyph alike. */
    val measured: Boolean = runCatching { measureGlyphs() }.getOrDefault(false)

    /**
     * @param phases for each sample, the row's center as a fraction of its line's height (0..1); null for 0.5
     * @param coverages for each sample, the fraction of its row the line covers; null when every line covers
     * its row fully
     * @param rowsPerLine raster rows per document line (below 1 when lines are compressed)
     * @param charWidth raster pixels per character cell, the device scale on HiDPI screens
     * @param indent left indent in raster pixels
     * @param accurate shape strokes by glyph ink instead of drawing CLEAN strokes
     * @param intensity text strength against the background in percent
     */
    fun render(
        text: CharSequence,
        width: Int,
        height: Int,
        starts: IntArray,
        ends: IntArray,
        rows: IntArray,
        foreground: Int,
        background: Int,
        tokens: List<Token>,
        phases: FloatArray? = null,
        coverages: FloatArray? = null,
        rowsPerLine: Double = 1.0,
        charWidth: Int = 1,
        indent: Int = INDENT,
        accurate: Boolean = false,
        intensity: Int = 100,
        checkCanceled: (() -> Unit)? = null,
    ): BufferedImage {
        require(starts.size == ends.size && starts.size == rows.size &&
            (phases == null || phases.size == starts.size) && (coverages == null || coverages.size == starts.size)) {
            "Matching sample arrays are required"
        }
        val cell = charWidth.coerceIn(1, 4)
        val imageWidth = width.coerceIn(40, MAX_WIDTH)
        val imageHeight = height.coerceIn(1, MAX_HEIGHT)
        val left = indent.coerceIn(0, imageWidth / 2)
        val strength = 256 * intensity.coerceIn(0, 100) / 100
        val length = text.length
        for (i in starts.indices) {
            if ((i and 63) == 0) checkCanceled?.invoke()
            require(starts[i] in 0..ends[i] && ends[i] <= length && rows[i] in 0 until imageHeight) {
                "Invalid sample at index $i"
            }
        }
        // Copy the sorted token list once: binary search must not depend on list implementation.
        val colors = tokens.toTypedArray()
        var previous = -1
        for (i in colors.indices) {
            if ((i and 63) == 0) checkCanceled?.invoke()
            val token = colors[i]
            require(token.start >= 0 && token.start >= previous && token.end >= token.start && token.end <= length) {
                "Invalid or unsorted token at index $i"
            }
            previous = token.start
        }

        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB)
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        pixels.fill(background and 0xffffff)
        val columns = (imageWidth - left - 6) / cell
        if (columns <= 0) return image
        // Per row, each column sums color weighted by glyph strength times line coverage, then the average
        // color is blended over the background by the total (capped) weight.
        val red = FloatArray(columns)
        val green = FloatArray(columns)
        val blue = FloatArray(columns)
        val ink = FloatArray(columns)

        fun flush(row: Int) {
            for (column in 0 until columns) {
                val total = ink[column]
                if (total <= 0) continue
                val rgb = ((red[column] / total).toInt() shl 16) or ((green[column] / total).toInt() shl 8) or
                    (blue[column] / total).toInt()
                val value = blend(rgb, background, minOf(256, (total * 256).roundToInt()))
                val x = left + column * cell
                for (dx in 0 until cell) if (x + dx < imageWidth) pixels[row * imageWidth + x + dx] = value
                red[column] = 0f
                green[column] = 0f
                blue[column] = 0f
                ink[column] = 0f
            }
        }

        // Samples arrive grouped by row; a row is flushed when the next row starts.
        var row = -1
        for (i in starts.indices) {
            if ((i and 63) == 0) checkCanceled?.invoke()
            if (rows[i] != row) {
                if (row >= 0) flush(row)
                row = rows[i]
            }
            val phase = phases?.get(i) ?: 0.5f
            val cover = (coverages?.get(i) ?: 1f).coerceIn(0f, 1f)
            if (cover <= 0) continue
            val limit = starts[i] + minOf(MAX_COLUMNS, ends[i] - starts[i])
            var column = 0
            var offset = starts[i]
            while (offset < limit && column < columns) {
                val c = text[offset]
                if (c == '\t') {
                    column += 4 - column % 4
                    offset++
                    continue
                }
                if (c == '\r' || c == '\n') break
                if (!c.isWhitespace()) {
                    val weight = (weight(c, phase, rowsPerLine, accurate) * strength shr 8) / 256f * cover
                    if (weight > 0) {
                        val rgb = color(colors, offset, foreground)
                        red[column] += ((rgb shr 16) and 0xff) * weight
                        green[column] += ((rgb shr 8) and 0xff) * weight
                        blue[column] += (rgb and 0xff) * weight
                        ink[column] += weight
                    }
                }
                // A supplementary code point occupies one cell, not two surrogate cells.
                if (Character.isHighSurrogate(c) && offset + 1 < limit && Character.isLowSurrogate(text[offset + 1])) offset++
                column++
                offset++
            }
        }
        if (row >= 0) flush(row)
        return image
    }

    private fun color(colors: Array<Token>, offset: Int, foreground: Int): Int {
        var low = 0
        var high = colors.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (colors[mid].start <= offset) low = mid + 1 else high = mid - 1
        }
        return if (high >= 0 && offset < colors[high].end) colors[high].rgb else foreground
    }

    /** Stroke strength (0..256) of glyph [c] on a raster row at [phase] of a line spanning [rowsPerLine] rows. */
    fun weight(c: Char, phase: Float, rowsPerLine: Double, accurate: Boolean): Int {
        // With three or more rows per line the first row stays light: the gap between code lines.
        val firstRow = rowsPerLine >= 3 && phase < 1 / rowsPerLine
        if (!accurate) return if (firstRow) 32 else 256
        val ascii = c.code < 128
        val upper = if (ascii) top[c.code] else 180
        val lower = if (ascii) bottom[c.code] else 180
        return when {
            rowsPerLine < 2 -> (upper + lower) / 2
            firstRow -> upper / 6
            phase < 0.55f -> upper
            else -> lower
        }
    }

    /** Average ink of a glyph on a single-row line, 0..256. */
    fun coverage(c: Char): Int = weight(c, 0.5f, 1.0, true)

    fun blend(rgb: Int, background: Int, coverage: Int): Int {
        val r = (((rgb shr 16) and 0xff) * coverage + ((background shr 16) and 0xff) * (256 - coverage)) shr 8
        val g = (((rgb shr 8) and 0xff) * coverage + ((background shr 8) and 0xff) * (256 - coverage)) shr 8
        val b = ((rgb and 0xff) * coverage + (background and 0xff) * (256 - coverage)) shr 8
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * Rasterizes printable ASCII with the platform's monospaced font and measures the ink above and below the
     * top of the x-height, normalized so the inkiest glyph of each half is 256.
     */
    private fun measureGlyphs(): Boolean {
        val size = 48
        val image = BufferedImage(size, size * 2, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, size)
            val metrics = g.fontMetrics
            val baseline = metrics.ascent
            val bottomEdge = minOf(image.height, baseline + metrics.descent)
            val xHeight = g.font.createGlyphVector(g.fontRenderContext, "x").visualBounds.height.roundToInt()
            // Upper half: capitals and ascenders above the x-height; lower half: the x-height body and descenders.
            val split = (baseline - xHeight).coerceIn(1, bottomEdge - 1)
            val data = (image.raster.dataBuffer as DataBufferByte).data
            val upper = DoubleArray(128)
            val lower = DoubleArray(128)
            g.color = Color.WHITE
            for (code in 33 until 127) {
                data.fill(0)
                g.drawString(code.toChar().toString(), 0, baseline)
                for (y in 0 until bottomEdge) for (x in 0 until size) {
                    val value = data[y * size + x].toInt() and 0xff
                    if (y < split) upper[code] += value.toDouble() else lower[code] += value.toDouble()
                }
            }
            val maxUpper = upper.max()
            val maxLower = lower.max()
            if (maxUpper <= 0 || maxLower <= 0) return false
            for (code in 33 until 127) {
                // A floor keeps tiny glyphs (dots, commas) visible at minimap scale.
                top[code] = maxOf(40, (upper[code] / maxUpper * 256).roundToInt())
                bottom[code] = maxOf(40, (lower[code] / maxLower * 256).roundToInt())
            }
            return true
        } finally {
            g.dispose()
        }
    }
}

package dev.codescroll

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Maps document lines to minimap pixels.
 *
 * [scale] is map pixels per document line; [origin] is the map-document y shown at the top of the panel.
 * The viewport frame is the editor's visible area at map scale, clamped to the document. In proportional
 * mode the map scrolls so that the frame travels from the top of the panel to its bottom in step with
 * the editor, instead of staying centered.
 */
data class MapGeometry(val lines: Int, val height: Int, val scale: Double, val origin: Double) {
    val documentHeight: Double get() = lines * scale

    fun y(line: Double): Double = line * scale - origin

    fun line(y: Double): Int = floor((y + origin) / scale).toInt().coerceIn(0, lines - 1)

    /** Panel y of the frame's top for an editor showing [spanLines] lines from [topLine]. */
    fun frameTop(topLine: Double, spanLines: Double): Double {
        val frame = frameHeight(spanLines)
        return (topLine * scale).coerceIn(0.0, max(0.0, documentHeight - frame)) - origin
    }

    fun frameHeight(spanLines: Double): Double = frameHeight(documentHeight, spanLines * scale)

    /** The same mapping with a different first row, for a raster that covers part of the document. */
    fun window(windowHeight: Int, windowOrigin: Double): MapGeometry =
        MapGeometry(lines, max(1, windowHeight), scale, windowOrigin)

    companion object {
        /** Upper bound for Fill, so a three-line file does not turn into three solid blocks. */
        const val MAX_FILL_SCALE = 16.0
        /** The frame never gets thinner than this, unless the document itself is shorter. */
        const val MIN_VIEWPORT = 8.0

        /**
         * @param topLine first visible line, fractional when the editor is scrolled mid-line
         * @param spanLines how many lines the editor's visible area spans
         * @param fill with [fit], stretch short documents beyond pixels-per-line
         */
        fun forScroll(
            lines: Int, height: Int, fit: Boolean, fill: Boolean, pixelsPerLine: Int,
            topLine: Double, spanLines: Double,
        ): MapGeometry {
            val lineCount = max(1, lines)
            val panel = height.coerceIn(1, 4096)
            val perLine = pixelsPerLine.coerceIn(1, 8).toDouble()
            val available = panel.toDouble() / lineCount
            val scale = when {
                !fit -> perLine
                fill -> min(MAX_FILL_SCALE, available)
                else -> min(perLine, available)
            }
            val documentHeight = lineCount * scale
            val maxOrigin = max(0.0, documentHeight - panel)
            var origin = 0.0
            if (maxOrigin > 0) {
                val frame = frameHeight(documentHeight, spanLines * scale)
                val maxFrameTop = max(0.0, documentHeight - frame)
                val frameTop = (topLine * scale).coerceIn(0.0, maxFrameTop)
                // Move the map at the rate that keeps the frame's position proportional to the scroll...
                origin = if (maxFrameTop > 0) frameTop / maxFrameTop * maxOrigin else 0.0
                // ...but keep the whole frame inside the panel; a frame taller than the panel shows its top.
                val lowest = max(0.0, frameTop + frame - panel)
                val highest = min(frameTop, maxOrigin)
                origin = if (lowest > highest) highest else origin.coerceIn(lowest, highest)
            }
            return MapGeometry(lineCount, panel, scale, origin)
        }

        /** Convenience for whole visible lines [visibleStart, visibleEnd). */
        fun create(
            lines: Int, height: Int, fit: Boolean, fill: Boolean = false, pixelsPerLine: Int,
            visibleStart: Int, visibleEnd: Int,
        ): MapGeometry = forScroll(
            lines, height, fit, fill, pixelsPerLine, visibleStart.toDouble(), (visibleEnd - visibleStart).toDouble(),
        )

        private fun frameHeight(documentHeight: Double, frame: Double): Double =
            min(documentHeight, max(MIN_VIEWPORT, frame))
    }
}

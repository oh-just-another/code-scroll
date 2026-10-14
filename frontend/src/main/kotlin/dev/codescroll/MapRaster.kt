package dev.codescroll

import com.intellij.lang.Language
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Background rasterization of document lines; runs inside a read action, never on the EDT. */
internal object MapRaster {
    private val COMMENT_KEYS = setOf(
        DefaultLanguageHighlighterColors.LINE_COMMENT, DefaultLanguageHighlighterColors.BLOCK_COMMENT,
        DefaultLanguageHighlighterColors.DOC_COMMENT,
    )
    /** Dimmed comments keep this share (of 256) of their color over the background. */
    private const val COMMENT_STRENGTH = 96
    private const val ALPHA = 0xff shl 24

    /** Whether a key is, or falls back to, one of the default comment keys. */
    private fun isComment(key: TextAttributesKey): Boolean {
        var current: TextAttributesKey? = key
        var depth = 0
        while (current != null && depth++ < 10) {
            if (current in COMMENT_KEYS) return true
            current = current.fallbackAttributeKey
        }
        return false
    }

    /** Bounds syntax-coloring work per frame; later samples fall back to the default foreground. */
    private const val MAX_TOKENS = 200_000
    /** Lines sampled per raster row when several share it; their coverage is averaged (antialiasing). */
    private const val MAX_LINES_PER_ROW = 8

    /** Languages where `#` never starts a comment; elsewhere (Python, shell, YAML, unknown) it may. */
    private val NO_HASH_COMMENTS = setOf(
        "java", "kt", "kts", "scala", "groovy", "c", "h", "cc", "cpp", "hpp", "cs", "m", "mm", "swift", "go", "rs",
        "dart", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "css", "scss", "sass", "less", "json", "jsonc",
        "html", "htm", "xml", "sql", "proto",
    )
    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdx", "mkd")

    fun hashComments(file: VirtualFile?): Boolean =
        file?.extension?.lowercase(Locale.ROOT)?.let { it !in NO_HASH_COMMENTS } ?: true

    fun isMarkdown(file: VirtualFile?): Boolean {
        if (file == null) return false
        if (file.extension?.lowercase(Locale.ROOT) in MARKDOWN_EXTENSIONS) return true
        return file.fileType.name.equals("markdown", ignoreCase = true)
    }

    /** Samples the rows of [device] (device-pixel geometry) and renders them antialiased. */
    fun rasterize(
        document: Document, text: CharSequence, device: MapGeometry, width: Int, charWidth: Int,
        highlighter: EditorHighlighter?, colors: TokenColors?, foreground: Int, background: Int,
        accurate: Boolean, intensity: Int, textless: Boolean,
    ): BufferedImage {
        val capacity = if (textless) 0 else device.height * MAX_LINES_PER_ROW
        val starts = IntArray(capacity)
        val ends = IntArray(capacity)
        val rows = IntArray(capacity)
        val phases = FloatArray(capacity)
        val coverages = FloatArray(capacity)
        var samples = 0
        val lineScale = device.scale
        val lineCount = document.lineCount
        var y = 0
        while (y < device.height && !textless) {
            if ((y and 63) == 0) ProgressManager.checkCanceled()
            val rowTop = y + device.origin
            if (rowTop >= device.documentHeight) break
            // Every line overlapping this row contributes by the share of the row it covers.
            val first = floor(rowTop / lineScale).toInt()
            val last = min(lineCount - 1, floor((rowTop + 1 - 1e-9) / lineScale).toInt())
            val count = last - first + 1
            if (count > 0) {
                val taken = min(count, MAX_LINES_PER_ROW)
                // When more lines share a row than are sampled, the sampled ones stand in for the rest.
                val stand = count.toDouble() / taken
                for (k in 0 until taken) {
                    val line = if (taken == count) first + k else first + (k.toLong() * (count - 1) / max(1, taken - 1)).toInt()
                    val lineTop = line * lineScale
                    val overlapTop = max(rowTop, lineTop)
                    val overlapBottom = min(rowTop + 1, lineTop + lineScale)
                    if (overlapBottom <= overlapTop) continue
                    // A write action cancels this read before stale line offsets are published.
                    starts[samples] = document.getLineStartOffset(line)
                    ends[samples] = document.getLineEndOffset(line)
                    phases[samples] = (((overlapTop + overlapBottom) / 2 - lineTop) / lineScale).coerceIn(0.0, 0.999).toFloat()
                    coverages[samples] = min(1.0, (overlapBottom - overlapTop) * stand).toFloat()
                    rows[samples++] = y
                }
            }
            y++
        }
        val tokens = if (highlighter == null || colors == null) emptyList()
        else tokens(highlighter, colors, text.length, starts, ends, samples)
        return RasterRenderer.render(
            text, width, device.height, starts.copyOf(samples), ends.copyOf(samples), rows.copyOf(samples),
            foreground, background, tokens,
            phases = phases.copyOf(samples), coverages = coverages.copyOf(samples), rowsPerLine = device.scale,
            charWidth = charWidth, indent = RasterRenderer.INDENT * charWidth, accurate = accurate,
            intensity = intensity, checkCanceled = ProgressManager::checkCanceled,
        )
    }

    /** Colors only the sampled ranges; tokens stay sorted by start offset as the renderer requires. */
    private fun tokens(
        highlighter: EditorHighlighter, colors: TokenColors, length: Int, starts: IntArray, ends: IntArray, samples: Int,
    ): List<RasterRenderer.Token> {
        val tokens = ArrayList<RasterRenderer.Token>()
        var previousStart = -1
        for (i in 0 until samples) {
            if (tokens.size >= MAX_TOKENS) break
            if ((i and 63) == 0) ProgressManager.checkCanceled()
            if (ends[i] <= starts[i]) continue
            val iterator = highlighter.createIterator(starts[i])
            // Only the first MAX_COLUMNS characters are drawn: a minified megabyte line must not be walked.
            val drawnEnd = min(ends[i], starts[i] + RasterRenderer.MAX_COLUMNS)
            var steps = 0
            while (!iterator.atEnd() && iterator.start < drawnEnd && tokens.size < MAX_TOKENS) {
                if ((++steps and 255) == 0) ProgressManager.checkCanceled()
                val start = iterator.start.coerceIn(0, length)
                val end = iterator.end.coerceIn(start, length)
                val color = colors.color(iterator.tokenType)
                if (color != -1 && start >= previousStart) {
                    tokens += RasterRenderer.Token(start, end, color)
                    previousStart = start
                }
                iterator.advance()
            }
        }
        return tokens
    }

    /**
     * Resolves token colors with this render's own syntax highlighters (one per token language, so injected
     * and layered languages keep their colors) instead of the editor highlighter's attribute cache, which is a
     * plain map the EDT fills concurrently.
     */
    class TokenColors(
        private val scheme: EditorColorsScheme, private val project: Project?, private val file: VirtualFile?,
        private val dimComments: Boolean = false,
    ) {
        private val highlighters = HashMap<Language, SyntaxHighlighter?>()
        private val colors = HashMap<IElementType, Int>()

        /** Foreground RGB of a token type, or -1 for the default foreground. */
        fun color(type: IElementType?): Int {
            if (type == null) return -1
            colors[type]?.let { return it }
            val highlighter = highlighters.getOrPut(type.language) {
                SyntaxHighlighterFactory.getSyntaxHighlighter(type.language, project, file)
                    ?: file?.let { SyntaxHighlighterFactory.getSyntaxHighlighter(it.fileType, project, it) }
            }
            val keys = highlighter?.getTokenHighlights(type).orEmpty()
            // Later keys are layered over earlier ones, as in the editor.
            var rgb = keys.mapNotNull { scheme.getAttributes(it)?.foregroundColor?.rgb }.lastOrNull() ?: -1
            if (dimComments && keys.any(::isComment)) {
                val base = if (rgb == -1) scheme.defaultForeground.rgb else rgb
                rgb = RasterRenderer.blend(base, scheme.defaultBackground.rgb, COMMENT_STRENGTH) or ALPHA
            }
            colors[type] = rgb
            return rgb
        }
    }
}

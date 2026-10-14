package dev.codescroll

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.EditorKind
import java.awt.Color
import java.util.Locale

@Service(Service.Level.APP)
@State(name = "CodeScrollSettings", storages = [Storage("CodeScroll.xml")])
class CodeScrollSettings : PersistentStateComponent<CodeScrollSettings.State> {
    enum class RenderStyle { CLEAN, ACCURATE }
    enum class ClickTiming { DOWN, UP, NONE }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        state.validate()
        this.state = state
    }

    /**
     * All options. A data class: the XML serializer gets a no-argument constructor from the defaults, and the
     * settings form compares and copies whole states.
     */
    data class State(
        var enabled: Boolean = true,
        var leftAligned: Boolean = false,
        var fit: Boolean = false,
        var fill: Boolean = false,
        var syntaxColors: Boolean = true,
        var renderStyle: RenderStyle = RenderStyle.CLEAN,
        /** Strength of minimap text against the editor background, in percent. */
        var textIntensity: Int = 70,
        var pixelsPerLine: Int = 3,
        var hideScrollbar: Boolean = false,
        var autoWidth: Boolean = true,
        var lockWidth: Boolean = false,
        var width: Int = 110,
        var diffWidth: Int = 60,
        var previewWidth: Int = 70,
        var consoleWidth: Int = 60,
        var untypedWidth: Int = 60,
        /** Width of the minimap in the rendered Markdown preview, in CSS pixels. */
        var markdownMapWidth: Int = 96,
        // Empty colors follow the editor color scheme; RRGGBB values are custom picker choices.
        var viewportColor: String = "",
        var viewportBorderColor: String = "",
        var caretLineColor: String = "",
        var selectionColor: String = "",
        var markerLabelColor: String = "",
        var declarationLabelColor: String = "",
        var viewportOpacity: Int = 38,
        var viewportBorderOpacity: Int = 110,
        var viewportBorderWidth: Int = 0,
        var clickTiming: ClickTiming = ClickTiming.DOWN,
        var clickByMousePosition: Boolean = false,
        var scrollOnly: Boolean = false,
        var showPreview: Boolean = true,
        var previewLineNumbers: Boolean = true,
        var previewWheel: Boolean = false,
        var hoverDelayMs: Int = 250,
        var hoverOnly: Boolean = false,
        var showQuickHide: Boolean = true,
        var showRenderedMarkdown: Boolean = true,
        /** Navigating with the rendered Markdown map also scrolls the source editor. */
        var markdownSyncSource: Boolean = true,
        var showMain: Boolean = true,
        var showDiff: Boolean = true,
        var showPreviewEditors: Boolean = true,
        var showConsole: Boolean = false,
        var emptyConsole: Boolean = true,
        var showUntyped: Boolean = false,
        var emptyUntyped: Boolean = true,
        var minLines: Int = 0,
        var maxLines: Int = 1_000_000,
        var outOfRangeEmpty: Boolean = false,
        var excludedExtensions: String = "ipynb",
        var debounceMs: Int = 120,
        /** Master switch for error/warning marks; the two below select severities. */
        var showDiagnostics: Boolean = true,
        var showErrors: Boolean = true,
        var showWarnings: Boolean = true,
        var fullLineDiagnostics: Boolean = false,
        var showVcs: Boolean = true,
        var showBookmarks: Boolean = true,
        var showBreakpoints: Boolean = true,
        /** Highlight every occurrence of the selected text on the map. */
        var showSelectionOccurrences: Boolean = true,
        /** Draw comments fainter than code on the map. */
        var dimComments: Boolean = false,
        var showSearch: Boolean = true,
        var fullLineMarkup: Boolean = false,
        var showMarkers: Boolean = true,
        var markerWords: String = "TODO,FIXME,MARK,region",
        var markerRegex: String = "",
        var showMethods: Boolean = true,
        // Methods are opt-in: labelling every method buries the code under labels. Types are always labelled.
        var methodNames: String = "",
        var methodAnnotations: String = "",
        var markerFontSize: Int = 10,
    ) {
        fun widthFor(kind: EditorKind?): Int = when (kind) {
            EditorKind.DIFF -> diffWidth
            EditorKind.PREVIEW -> previewWidth
            EditorKind.CONSOLE -> consoleWidth
            EditorKind.UNTYPED -> untypedWidth
            else -> width
        }

        fun setWidthFor(kind: EditorKind?, value: Int) {
            val bounded = value.coerceIn(40, 300)
            when (kind) {
                EditorKind.DIFF -> diffWidth = bounded
                EditorKind.PREVIEW -> previewWidth = bounded
                EditorKind.CONSOLE -> consoleWidth = bounded
                EditorKind.UNTYPED -> untypedWidth = bounded
                else -> width = bounded
            }
        }

        /** Clamps every value into its range; persisted files are not necessarily written by the form. */
        fun validate() {
            if (runCatching { MarkerScanner.compileMarkerRegex(markerRegex) }.isFailure) markerRegex = ""
            viewportColor = normalizeColor(viewportColor)
            viewportBorderColor = normalizeColor(viewportBorderColor)
            caretLineColor = normalizeColor(caretLineColor)
            selectionColor = normalizeColor(selectionColor)
            markerLabelColor = normalizeColor(markerLabelColor)
            declarationLabelColor = normalizeColor(declarationLabelColor)
            textIntensity = textIntensity.coerceIn(20, 100)
            width = width.coerceIn(40, 300)
            diffWidth = diffWidth.coerceIn(40, 300)
            previewWidth = previewWidth.coerceIn(40, 300)
            consoleWidth = consoleWidth.coerceIn(40, 300)
            untypedWidth = untypedWidth.coerceIn(40, 300)
            markdownMapWidth = markdownMapWidth.coerceIn(40, 300)
            hoverDelayMs = hoverDelayMs.coerceIn(0, 2_000)
            viewportOpacity = viewportOpacity.coerceIn(0, 255)
            viewportBorderOpacity = viewportBorderOpacity.coerceIn(0, 255)
            viewportBorderWidth = viewportBorderWidth.coerceIn(0, 8)
            markerFontSize = markerFontSize.coerceIn(6, 36)
            pixelsPerLine = pixelsPerLine.coerceIn(1, 8)
            minLines = minLines.coerceIn(0, 1_000_000)
            maxLines = maxLines.coerceIn(minLines, 1_000_000)
            debounceMs = debounceMs.coerceIn(50, 2_000)
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): CodeScrollSettings =
            ApplicationManager.getApplication().getService(CodeScrollSettings::class.java)

        /** Shortcut for the live state. */
        val current: State get() = getInstance().state

        /**
         * Resolves a configured color: a custom RRGGBB value when set, otherwise the color-scheme fallback.
         * An empty value means "follow the editor color scheme".
         */
        @JvmStatic
        fun color(hex: String?, schemeColor: Color, opacity: Int): Color {
            val normalized = normalizeColor(hex)
            val rgb = if (normalized.isEmpty()) schemeColor.rgb else normalized.toInt(16)
            return Color((rgb and 0xffffff) or (opacity.coerceIn(0, 255) shl 24), true)
        }

        @JvmStatic
        fun hex(color: Color?): String =
            if (color == null) "" else String.format(Locale.ROOT, "%06X", color.rgb and 0xffffff)

        /** Six upper-case hex digits, or an empty string for "use the color scheme". */
        @JvmStatic
        fun normalizeColor(value: String?): String {
            val hex = value?.trim()?.removePrefix("#") ?: return ""
            return if (hex.matches(Regex("[0-9a-fA-F]{6}"))) hex.uppercase(Locale.ROOT) else ""
        }
    }
}

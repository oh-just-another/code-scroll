package dev.codescroll

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.Color
import kotlin.reflect.KMutableProperty0

/**
 * Settings | Editor | Code Scroll. Built with the Kotlin UI DSL: every option has its explanation as a comment
 * under it, and the page is indexed for the settings search. The form edits a working copy; Apply validates it
 * and replaces the stored state.
 */
class CodeScrollConfigurable : BoundSearchableConfigurable("Code Scroll", "dev.codescroll.settings", "dev.codescroll.settings") {
    private val working = CodeScrollSettings.State()
    private val colors = ArrayList<ColorChoice>()

    /**
     * A color that follows the editor color scheme until "Custom" is selected; the picker then stores an explicit
     * RRGGBB value. While not custom, the picker previews the scheme color.
     */
    private class ColorChoice(val property: KMutableProperty0<String>, val schemeColor: (EditorColorsScheme) -> Color) {
        val custom = JBCheckBox("Custom")
        val picker = ColorPanel()

        fun schemeColor(): Color = schemeColor(EditorColorsManager.getInstance().globalScheme)

        fun reset() {
            val hex = CodeScrollSettings.normalizeColor(property.get())
            custom.isSelected = hex.isNotEmpty()
            picker.selectedColor = if (hex.isEmpty()) schemeColor() else Color(hex.toInt(16))
            picker.isEnabled = custom.isSelected
        }

        fun value(): String = if (custom.isSelected) CodeScrollSettings.hex(picker.selectedColor) else ""

        init {
            custom.addActionListener {
                if (!custom.isSelected) picker.selectedColor = schemeColor()
                picker.isEnabled = custom.isSelected
            }
        }
    }

    override fun createPanel(): DialogPanel {
        XmlSerializerUtil.copyBean(CodeScrollSettings.current, working)
        val w = working
        return panel {
            group("Appearance") {
                checkbox(w::enabled, "Enable minimap", "Show the Code Scroll minimap globally.")
                checkbox(w::leftAligned, "Align left", "Place the minimap on the left side of the editor.")
                checkbox(w::fit, "Fit to editor height", "Squeeze long documents into the panel height instead of scrolling the map.")
                checkbox(w::fill, "Fill editor height", "With Fit, stretch short documents to the full height.")
                checkbox(w::syntaxColors, "Syntax colors", "Use the editor's syntax highlighting colors in the minimap.")
                checkbox(w::dimComments, "Dim comments", "Draw comments fainter than code, so the code structure stands out.")
                row("Render style:") {
                    comboBox(CodeScrollSettings.RenderStyle.entries).bindItem(w::renderStyle.toNullableProperty())
                        .comment("CLEAN draws solid strokes with a light row between lines; ACCURATE also shows capitals, ascenders and descenders.")
                }
                number(w::textIntensity, "Text intensity (%):", 20..100, "How strongly minimap text stands out from the editor background.")
                number(w::pixelsPerLine, "Pixels per line:", 1..8, "Line height on the map when not fitting the document.")
                checkbox(w::hideScrollbar, "Hide editor scrollbar", "Hide the editor's own vertical scrollbar while a minimap is shown.")
            }
            group("Width") {
                checkbox(w::autoWidth, "Automatic width", "Reduce the minimap width in narrow and split editors.")
                checkbox(w::lockWidth, "Lock width", "Disable resizing by dragging the minimap edge.")
                number(w::width, "Main editors (px):", 40..300, "Width in regular file editors.")
                number(w::diffWidth, "Diff editors (px):", 40..300, "Width in diff and merge views.")
                number(w::previewWidth, "Preview editors (px):", 40..300, "Width in preview editors.")
                number(w::consoleWidth, "Consoles (px):", 40..300, "Width in console output.")
                number(w::untypedWidth, "Untyped editors (px):", 40..300, "Width in editors without a file type.")
            }
            group("Colors") {
                color(w::viewportColor, "Viewport:", { it.key(EditorColors.SELECTION_BACKGROUND_COLOR) },
                    "Fill of the visible-area frame. Follows the scheme's selection background unless Custom.")
                number(w::viewportOpacity, "Viewport opacity:", 0..255, "Alpha of the frame fill; it brightens while hovered and dragged.")
                color(w::viewportBorderColor, "Viewport border:", { it.key(EditorColors.SELECTION_BACKGROUND_COLOR) },
                    "Border of the visible-area frame. Follows the scheme's selection background unless Custom.")
                number(w::viewportBorderOpacity, "Border opacity:", 0..255, "Alpha of the frame border.")
                number(w::viewportBorderWidth, "Border width (px):", 0..8, "Thickness of the frame border; 0 hides it.")
                color(w::caretLineColor, "Caret line:", { it.key(EditorColors.CARET_COLOR) },
                    "Line marking the caret position. Follows the scheme's caret color unless Custom.")
                color(w::selectionColor, "Selection:", { it.key(EditorColors.SELECTION_BACKGROUND_COLOR) },
                    "Band marking selected lines. Follows the scheme's selection background unless Custom.")
                color(w::markerLabelColor, "Marker labels:", { it.foreground(CodeInsightColors.TODO_DEFAULT_ATTRIBUTES) },
                    "TODO, region and custom marker labels. Follows the scheme's TODO color unless Custom.")
                color(w::declarationLabelColor, "Declaration labels:",
                    { it.foreground(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION) },
                    "Class and method labels. Follows the scheme's function declaration color unless Custom.")
            }
            group("Navigation and Preview") {
                row("Click timing:") {
                    comboBox(CodeScrollSettings.ClickTiming.entries).bindItem(w::clickTiming.toNullableProperty())
                        .comment("DOWN: the frame jumps under the pointer and can be dragged on. UP: jump on release. NONE: drag the frame only.")
                }
                checkbox(w::clickByMousePosition, "Click by mouse position", "Place the caret at the clicked column, not just the clicked line.")
                checkbox(w::scrollOnly, "Scroll only", "Scroll the editor on click without moving the caret or selection.")
                checkbox(w::showPreview, "Show hover preview", "Show the code under the pointer in a popup while hovering.")
                checkbox(w::previewLineNumbers, "Line numbers in preview", "Show the document's line numbers next to the hover preview.")
                checkbox(w::previewWheel, "Scroll preview with mouse wheel", "While the preview is open, the wheel moves the preview instead of the editor.")
                number(w::hoverDelayMs, "Hover delay (ms):", 0..2_000, "Delay before the preview or a hover-only minimap appears.")
                checkbox(w::hoverOnly, "Show only on hover", "Keep the minimap collapsed to a narrow strip until the pointer rests on it.")
                checkbox(w::showQuickHide, "Show quick hide button", "Show a × button that hides the minimap in the current editor.")
                checkbox(w::showRenderedMarkdown, "Rendered Markdown minimap", "Show a minimap inside the rendered Markdown preview.")
                checkbox(w::markdownSyncSource, "Sync source with Markdown minimap", "Scrolling with the preview minimap also scrolls the Markdown source editor.")
            }
            group("Editors") {
                checkbox(w::showMain, "Main editors", "Show the minimap in regular file editors.")
                checkbox(w::showDiff, "Diff editors", "Show the minimap in diff and merge views.")
                checkbox(w::showPreviewEditors, "Preview editors", "Show the minimap in preview editors.")
                checkbox(w::showConsole, "Consoles", "Show the minimap in console output.")
                checkbox(w::emptyConsole, "Keep console minimap empty", "Draw only the viewport in consoles, without text.")
                checkbox(w::showUntyped, "Untyped editors", "Show the minimap in editors without a recognized file type.")
                checkbox(w::emptyUntyped, "Keep untyped minimap empty", "Draw only the viewport in untyped editors, without text.")
                number(w::minLines, "Minimum lines:", 0..1_000_000, "Hide the minimap for shorter documents; 0 disables the limit.")
                number(w::maxLines, "Maximum lines:", 0..1_000_000, "Hide the minimap for longer documents; raised to the minimum if lower.")
                checkbox(w::outOfRangeEmpty, "Keep an empty minimap outside the line limits", "Show an empty, still navigable minimap instead of hiding it.")
                text(w::excludedExtensions, "Excluded extensions:", "Comma-separated extensions without dots, for example ipynb,log.")
                number(w::debounceMs, "Update delay (ms):", 50..2_000, "Delay after edits before the minimap is redrawn.")
            }
            group("Markers and Highlights") {
                lateinit var diagnostics: Cell<JBCheckBox>
                row {
                    diagnostics = checkBox("Errors and warnings").bindSelected(w::showDiagnostics)
                        .comment("Marks on the far edge of the minimap. Also in the minimap context menu and View | Toggle Code Scroll Diagnostics.")
                }
                indent {
                    row { checkBox("Errors").bindSelected(w::showErrors).enabledIf(diagnostics.selected).comment("Show error marks.") }
                    row {
                        checkBox("Warnings").bindSelected(w::showWarnings).enabledIf(diagnostics.selected)
                            .comment("Show warning, weak warning and other inspection marks.")
                    }
                    row {
                        checkBox("Full-line diagnostics").bindSelected(w::fullLineDiagnostics).enabledIf(diagnostics.selected)
                            .comment("Draw diagnostics across the whole minimap line.")
                    }
                }
                checkbox(w::showVcs, "VCS changes", "Mark added, changed and deleted lines on the editor-facing edge.")
                checkbox(w::showBookmarks, "Bookmarks", "Mark bookmarked lines.")
                checkbox(w::showBreakpoints, "Breakpoints", "Mark lines with breakpoints on the editor-facing edge.")
                checkbox(w::showSelectionOccurrences, "Occurrences of the selection", "Highlight every occurrence of the selected text on the map.")
                checkbox(w::showSearch, "Search results and usages", "Highlight find results and highlighted usages.")
                checkbox(w::fullLineMarkup, "Full-line other markup", "Draw remaining editor highlights across the whole line.")
                checkbox(w::showMarkers, "Comment markers", "Label comments that contain the marker words or regex below.")
                text(w::markerWords, "Marker words:",
                    "Comma-separated. Words in capitals are case-sensitive; region and MARK only count at the start of a comment.")
                row("Marker regex:") {
                    textField().bindText(w::markerRegex).columns(COLUMNS_LARGE)
                        .comment("Literals, classes, anchors and simple escapes, one quantifier at most; no groups or alternation. Up to 256 characters.")
                        .validationOnApply { field ->
                            runCatching { MarkerScanner.compileMarkerRegex(field.text) }.exceptionOrNull()?.let { error(it.message ?: "Invalid regex.") }
                        }
                }
                checkbox(w::showMethods, "Declarations", "Label classes and types, and methods selected by the filters below.")
                row("Method names:") {
                    textArea().bindText(w::methodNames).rows(3).columns(COLUMNS_LARGE).align(AlignX.FILL)
                        .comment("Methods to label, comma- or newline-separated; * matches any text, ? one character. Empty labels no methods.")
                }
                row("Method annotations:") {
                    textArea().bindText(w::methodAnnotations).rows(3).columns(COLUMNS_LARGE).align(AlignX.FILL)
                        .comment("Comma- or newline-separated annotation filters, @ optional. Empty allows all methods.")
                }
                number(w::markerFontSize, "Label font size:", 6..36, "Size of marker and declaration labels.")
            }
            onReset { colors.forEach { it.reset() } }
            onIsModified { colors.any { it.value() != CodeScrollSettings.normalizeColor(it.property.get()) } }
            onApply { colors.forEach { it.property.set(it.value()) } }
        }
    }

    private fun Panel.checkbox(property: KMutableProperty0<Boolean>, text: String, comment: String) {
        row { checkBox(text).bindSelected(property).comment(comment) }
    }

    private fun Panel.number(property: KMutableProperty0<Int>, label: String, range: IntRange, comment: String) {
        row(label) { spinner(range).bindIntValue(property).comment(comment) }
    }

    private fun Panel.text(property: KMutableProperty0<String>, label: String, comment: String) {
        row(label) { textField().bindText(property).columns(COLUMNS_LARGE).comment(comment) }
    }

    private fun Panel.color(
        property: KMutableProperty0<String>, label: String, schemeColor: (EditorColorsScheme) -> Color, comment: String,
    ) {
        val choice = ColorChoice(property, schemeColor).also { colors += it }
        row(label) {
            cell(choice.picker)
            cell(choice.custom).comment(comment)
        }
    }

    override fun reset() {
        XmlSerializerUtil.copyBean(CodeScrollSettings.current, working)
        super.reset()
    }

    override fun isModified(): Boolean = super.isModified() || working != CodeScrollSettings.current

    override fun apply() {
        super.apply()
        val state = working.copy()
        state.validate()
        CodeScrollSettings.getInstance().loadState(state)
        CodeScrollManager.getInstance().refreshAll()
    }

    private companion object {
        fun EditorColorsScheme.key(key: ColorKey): Color = getColor(key) ?: defaultForeground

        fun EditorColorsScheme.foreground(key: TextAttributesKey): Color = getAttributes(key)?.foregroundColor ?: defaultForeground
    }
}

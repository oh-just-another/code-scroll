package dev.codescroll

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.util.NlsContexts

/**
 * Minimap colors that each editor color scheme can define (Settings | Editor | Color Scheme | Code Scroll), so light
 * and dark schemes get their own values. Every key falls back to the standard scheme color used before, and a custom
 * picker value in the Code Scroll settings still overrides the scheme.
 */
object CodeScrollColors {
    val VIEWPORT: ColorKey = ColorKey.createColorKeyWithFallback("CODE_SCROLL_VIEWPORT", EditorColors.SELECTION_BACKGROUND_COLOR)
    val VIEWPORT_BORDER: ColorKey = ColorKey.createColorKeyWithFallback("CODE_SCROLL_VIEWPORT_BORDER", EditorColors.SELECTION_BACKGROUND_COLOR)
    val CARET_LINE: ColorKey = ColorKey.createColorKeyWithFallback("CODE_SCROLL_CARET_LINE", EditorColors.CARET_COLOR)
    val SELECTION: ColorKey = ColorKey.createColorKeyWithFallback("CODE_SCROLL_SELECTION", EditorColors.SELECTION_BACKGROUND_COLOR)
    val MARKER_LABEL: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("CODE_SCROLL_MARKER_LABEL", CodeInsightColors.TODO_DEFAULT_ATTRIBUTES)
    val DECLARATION_LABEL: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("CODE_SCROLL_DECLARATION_LABEL", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
}

class CodeScrollColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): @NlsContexts.ConfigurableName String = "Code Scroll"
    override fun getIcon() = null
    override fun getHighlighter() = PlainSyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf(
        "marker" to CodeScrollColors.MARKER_LABEL,
        "declaration" to CodeScrollColors.DECLARATION_LABEL,
    )

    override fun getDemoText() = """
        Code Scroll minimap labels:
        <marker>TODO: comment marker label</marker>
        <declaration>OrderService</declaration> (class or method label)

        The viewport, its border, the caret line and the selection band are colors below.
    """.trimIndent()

    override fun getAttributeDescriptors() = arrayOf(
        AttributesDescriptor("Marker label", CodeScrollColors.MARKER_LABEL),
        AttributesDescriptor("Declaration label", CodeScrollColors.DECLARATION_LABEL),
    )

    override fun getColorDescriptors() = arrayOf(
        ColorDescriptor("Viewport", CodeScrollColors.VIEWPORT, ColorDescriptor.Kind.BACKGROUND),
        ColorDescriptor("Viewport border", CodeScrollColors.VIEWPORT_BORDER, ColorDescriptor.Kind.FOREGROUND),
        ColorDescriptor("Caret line", CodeScrollColors.CARET_LINE, ColorDescriptor.Kind.FOREGROUND),
        ColorDescriptor("Selection", CodeScrollColors.SELECTION, ColorDescriptor.Kind.BACKGROUND),
    )
}

package dev.codescroll

/**
 * Sorts editor highlighters into minimap categories from public highlighter properties only. The frontend
 * module cannot depend on backend daemon classes such as HighlightInfo, so this is a deliberately
 * conservative classification rather than an exact one.
 */
object MarkupClassifier {
    enum class Kind { DIAGNOSTIC, VCS, BOOKMARK, SEARCH, OTHER }

    /** External names of EditorColors keys used by Find, Highlight Usages and identifier-under-caret. */
    val SEARCH_KEYS: Set<String> = setOf(
        "SEARCH_RESULT_ATTRIBUTES", "WRITE_SEARCH_RESULT_ATTRIBUTES", "TEXT_SEARCH_RESULT_ATTRIBUTES",
        "IDENTIFIER_UNDER_CARET_ATTRIBUTES", "WRITE_IDENTIFIER_UNDER_CARET_ATTRIBUTES",
    )

    /**
     * Error highlighters use HighlighterLayer.ERROR (5000); warnings use WARNING (4000) and WEAK_WARNING
     * (3750). ELEMENT_UNDER_CARET (5500) and above are not errors.
     */
    fun isError(layer: Int): Boolean = layer in 5000 until 5500

    /**
     * @param attributesKey external name of the highlighter's text attributes key
     * @param thinStripe whether the highlighter asks for a thin error-stripe mark (VCS ranges do)
     * @param lineMarkerClass class name of the gutter line-marker renderer
     * @param gutterIconClass class name of the gutter icon renderer
     * @param hasStripeColor whether the highlighter has an error-stripe color in the current scheme
     */
    fun classify(
        attributesKey: String?, thinStripe: Boolean, lineMarkerClass: String?,
        gutterIconClass: String?, hasStripeColor: Boolean,
    ): Kind = when {
        attributesKey != null && attributesKey in SEARCH_KEYS -> Kind.SEARCH
        gutterIconClass.has("Bookmark") || lineMarkerClass.has("Bookmark") -> Kind.BOOKMARK
        lineMarkerClass.has("LineStatus") || (thinStripe && lineMarkerClass != null) -> Kind.VCS
        hasStripeColor && !thinStripe -> Kind.DIAGNOSTIC
        else -> Kind.OTHER
    }

    private fun String?.has(part: String) = this != null && contains(part)
}

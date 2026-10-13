package dev.codescroll;

import java.util.Set;

/**
 * Sorts editor highlighters into minimap categories from public highlighter properties only.
 * The frontend module cannot depend on backend daemon classes such as HighlightInfo, so this is a
 * deliberately conservative classification rather than an exact one.
 */
public final class MarkupClassifier {
    public enum Kind { DIAGNOSTIC, VCS, BOOKMARK, SEARCH, OTHER }

    /** External names of EditorColors keys used by Find, Highlight Usages and identifier-under-caret. */
    static final Set<String> SEARCH_KEYS = Set.of("SEARCH_RESULT_ATTRIBUTES", "WRITE_SEARCH_RESULT_ATTRIBUTES",
            "TEXT_SEARCH_RESULT_ATTRIBUTES", "IDENTIFIER_UNDER_CARET_ATTRIBUTES",
            "WRITE_IDENTIFIER_UNDER_CARET_ATTRIBUTES");

    private MarkupClassifier() {}

    /**
     * @param attributesKey    external name of the highlighter's text attributes key, or null
     * @param thinStripe       whether the highlighter asks for a thin error-stripe mark (VCS ranges do)
     * @param lineMarkerClass  class name of the gutter line-marker renderer, or null
     * @param gutterIconClass  class name of the gutter icon renderer, or null
     * @param hasStripeColor   whether the highlighter has an error-stripe color in the current scheme
     */
    public static Kind classify(String attributesKey, boolean thinStripe, String lineMarkerClass,
                                String gutterIconClass, boolean hasStripeColor) {
        if (attributesKey != null && SEARCH_KEYS.contains(attributesKey)) return Kind.SEARCH;
        if (contains(gutterIconClass, "Bookmark") || contains(lineMarkerClass, "Bookmark")) return Kind.BOOKMARK;
        if (contains(lineMarkerClass, "LineStatus") || (thinStripe && lineMarkerClass != null)) return Kind.VCS;
        if (hasStripeColor && !thinStripe) return Kind.DIAGNOSTIC;
        return Kind.OTHER;
    }

    private static boolean contains(String value, String part) {
        return value != null && value.contains(part);
    }
}

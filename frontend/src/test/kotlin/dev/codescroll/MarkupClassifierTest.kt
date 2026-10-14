package dev.codescroll

import dev.codescroll.MarkupClassifier.Kind.BOOKMARK
import dev.codescroll.MarkupClassifier.Kind.DIAGNOSTIC
import dev.codescroll.MarkupClassifier.Kind.OTHER
import dev.codescroll.MarkupClassifier.Kind.SEARCH
import dev.codescroll.MarkupClassifier.Kind.VCS
import dev.codescroll.MarkupClassifier.classify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkupClassifierTest {
    @Test
    fun searchKeysWinOverStripeColors() {
        for (key in MarkupClassifier.SEARCH_KEYS) assertEquals(SEARCH, classify(key, false, null, null, true))
    }

    @Test
    fun bookmarksAreRecognizedByTheirGutterRenderer() {
        assertEquals(BOOKMARK, classify(null, false, null, "com.intellij.ide.bookmark.ui.GutterLineBookmarkRenderer", false))
        assertEquals(BOOKMARK, classify(null, true, "x.BookmarkLineRenderer", null, true))
    }

    @Test
    fun vcsRangesUseThinStripesWithLineMarkers() {
        assertEquals(VCS, classify(null, true, "com.intellij.openapi.vcs.ex.LineStatusMarkerRenderer", null, true))
        assertEquals(VCS, classify(null, true, "some.Renderer", null, true))
        assertEquals(VCS, classify(null, false, "com.intellij.openapi.vcs.ex.LineStatusMarkerRendererWithPopup", null, false))
    }

    @Test
    fun stripeColoredHighlightersAreDiagnosticsAndTheRestIsOther() {
        assertEquals(DIAGNOSTIC, classify("ERRORS_ATTRIBUTES", false, null, null, true))
        assertEquals(DIAGNOSTIC, classify(null, false, null, "some.Icon", true))
        assertEquals(OTHER, classify(null, true, null, null, true))
        assertEquals(OTHER, classify("INJECTED_LANGUAGE_FRAGMENT", false, null, null, false))
        assertEquals(OTHER, classify(null, false, null, null, false))
    }

    @Test
    fun errorsAreSeparatedFromWarningsByHighlighterLayer() {
        assertTrue(MarkupClassifier.isError(5000))
        assertTrue(MarkupClassifier.isError(5100))
        assertFalse(MarkupClassifier.isError(5500))
        assertFalse(MarkupClassifier.isError(4000))
        assertFalse(MarkupClassifier.isError(3000))
    }
}

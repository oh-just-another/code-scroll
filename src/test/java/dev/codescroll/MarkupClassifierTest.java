package dev.codescroll;

import org.junit.jupiter.api.Test;

import static dev.codescroll.MarkupClassifier.Kind.*;
import static dev.codescroll.MarkupClassifier.classify;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkupClassifierTest {
    @Test
    void searchKeysWinOverStripeColors() {
        for (String key : MarkupClassifier.SEARCH_KEYS) assertEquals(SEARCH, classify(key, false, null, null, true));
    }

    @Test
    void bookmarksAreRecognizedByTheirGutterRenderer() {
        assertEquals(BOOKMARK, classify(null, false, null, "com.intellij.ide.bookmark.ui.GutterLineBookmarkRenderer", false));
        assertEquals(BOOKMARK, classify(null, true, "x.BookmarkLineRenderer", null, true));
    }

    @Test
    void vcsRangesUseThinStripesWithLineMarkers() {
        assertEquals(VCS, classify(null, true, "com.intellij.openapi.vcs.ex.LineStatusMarkerRenderer", null, true));
        assertEquals(VCS, classify(null, true, "some.Renderer", null, true));
        assertEquals(VCS, classify(null, false, "com.intellij.openapi.vcs.ex.LineStatusMarkerRendererWithPopup", null, false));
    }

    @Test
    void stripeColoredHighlightersAreDiagnosticsAndTheRestIsOther() {
        assertEquals(DIAGNOSTIC, classify("ERRORS_ATTRIBUTES", false, null, null, true));
        assertEquals(DIAGNOSTIC, classify(null, false, null, "some.Icon", true));
        assertEquals(OTHER, classify(null, true, null, null, true));
        assertEquals(OTHER, classify("INJECTED_LANGUAGE_FRAGMENT", false, null, null, false));
        assertEquals(OTHER, classify(null, false, null, null, false));
    }

    @Test
    void errorsAreSeparatedFromWarningsByHighlighterLayer() {
        assertEquals(true, MarkupClassifier.isError(5000));
        assertEquals(true, MarkupClassifier.isError(5100));
        assertEquals(false, MarkupClassifier.isError(5500));
        assertEquals(false, MarkupClassifier.isError(4000));
        assertEquals(false, MarkupClassifier.isError(3000));
    }
}

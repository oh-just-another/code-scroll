package dev.codescroll;

import com.intellij.openapi.editor.EditorKind;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

class CodeScrollSettingsTest {
    @Test
    void defaultsRetainLegacyFieldsAndInitializeNewOptions() {
        CodeScrollSettings.State state = new CodeScrollSettings.State();
        assertTrue(state.fit);
        assertEquals(110, state.width);
        assertFalse(state.fill);
        assertEquals(CodeScrollSettings.RenderStyle.CLEAN, state.renderStyle);
        assertEquals(CodeScrollSettings.ClickTiming.DOWN, state.clickTiming);
        assertFalse(state.clickByMousePosition);
        assertEquals(60, state.diffWidth);
        assertEquals(70, state.previewWidth);
        assertEquals(60, state.consoleWidth);
        assertEquals(60, state.untypedWidth);
        assertFalse(state.hoverOnly);
        assertTrue(state.showQuickHide);
        assertEquals(250, state.hoverDelayMs);
        assertTrue(state.emptyConsole);
        assertTrue(state.emptyUntyped);
        assertFalse(state.outOfRangeEmpty);
        assertEquals("A0A0A0", state.viewportColor);
        assertEquals("808080", state.viewportBorderColor);
        assertEquals(35, state.viewportOpacity);
        assertEquals(110, state.viewportBorderOpacity);
        assertEquals(1, state.viewportBorderWidth);
        assertTrue(state.showBookmarks);
        assertTrue(state.showVcs);
        assertTrue(state.showDiagnostics);
        assertTrue(state.showSearch);
        assertFalse(state.fullLineDiagnostics);
        assertFalse(state.fullLineMarkup);
        assertEquals("*", state.methodNames);
        assertEquals("", state.methodAnnotations);
        assertEquals("", state.markerRegex);
        assertEquals(10, state.markerFontSize);
        assertTrue(state.showRenderedMarkdown);
        assertFalse(state.previewWheel);
    }

    @Test
    void validatesBoundsNullDefaultsColorsAndUnsafeRegex() {
        CodeScrollSettings.State state = new CodeScrollSettings.State();
        state.width = Integer.MIN_VALUE;
        state.diffWidth = Integer.MAX_VALUE;
        state.previewWidth = 0;
        state.consoleWidth = 301;
        state.untypedWidth = -1;
        state.pixelsPerLine = 100;
        state.minLines = 2_000_000;
        state.maxLines = -1;
        state.debounceMs = 0;
        state.hoverDelayMs = -1;
        state.viewportOpacity = -1;
        state.viewportBorderOpacity = 256;
        state.viewportBorderWidth = -1;
        state.markerFontSize = 100;
        state.renderStyle = null;
        state.clickTiming = null;
        state.excludedExtensions = null;
        state.markerWords = null;
        state.methodNames = null;
        state.methodAnnotations = null;
        state.markerRegex = "(a+)+";
        state.viewportColor = " #aBc012 ";
        state.viewportBorderColor = "not a color";
        state.validate();
        assertEquals(40, state.width);
        assertEquals(300, state.diffWidth);
        assertEquals(40, state.previewWidth);
        assertEquals(300, state.consoleWidth);
        assertEquals(40, state.untypedWidth);
        assertEquals(8, state.pixelsPerLine);
        assertEquals(1_000_000, state.minLines);
        assertEquals(1_000_000, state.maxLines);
        assertEquals(50, state.debounceMs);
        assertEquals(0, state.hoverDelayMs);
        assertEquals(0, state.viewportOpacity);
        assertEquals(255, state.viewportBorderOpacity);
        assertEquals(0, state.viewportBorderWidth);
        assertEquals(36, state.markerFontSize);
        assertEquals(CodeScrollSettings.RenderStyle.CLEAN, state.renderStyle);
        assertEquals(CodeScrollSettings.ClickTiming.DOWN, state.clickTiming);
        assertEquals("", state.excludedExtensions);
        assertEquals("", state.markerWords);
        assertEquals("*", state.methodNames);
        assertEquals("", state.methodAnnotations);
        assertEquals("", state.markerRegex);
        assertEquals("ABC012", state.viewportColor);
        assertEquals("808080", state.viewportBorderColor);

        state.markerRegex = null;
        state.viewportColor = null;
        state.viewportBorderColor = null;
        state.hoverDelayMs = 3_000;
        state.viewportBorderWidth = 100;
        state.markerFontSize = 0;
        state.minLines = -1;
        state.maxLines = Integer.MAX_VALUE;
        state.pixelsPerLine = 0;
        state.debounceMs = 10_000;
        state.validate();
        assertEquals("", state.markerRegex);
        assertEquals("A0A0A0", state.viewportColor);
        assertEquals("808080", state.viewportBorderColor);
        assertEquals(2_000, state.hoverDelayMs);
        assertEquals(8, state.viewportBorderWidth);
        assertEquals(6, state.markerFontSize);
        assertEquals(0, state.minLines);
        assertEquals(1_000_000, state.maxLines);
        assertEquals(1, state.pixelsPerLine);
        assertEquals(2_000, state.debounceMs);
    }

    @Test
    void widthsAreIndependentForEveryEditorKindAndClampWhenSet() {
        CodeScrollSettings.State state = new CodeScrollSettings.State();
        for (EditorKind kind : EditorKind.values()) {
            state.setWidthFor(kind, 40 + kind.ordinal());
            assertEquals(40 + kind.ordinal(), state.widthFor(kind));
        }
        int main = state.width;
        state.setWidthFor(EditorKind.DIFF, 400);
        assertEquals(300, state.diffWidth);
        assertEquals(main, state.width);
        state.setWidthFor(EditorKind.PREVIEW, -1);
        assertEquals(40, state.previewWidth);
        state.setWidthFor(null, 125);
        assertEquals(125, state.widthFor(null));
        assertEquals(125, state.widthFor(EditorKind.MAIN_EDITOR));
        assertEquals(300, state.widthFor(EditorKind.DIFF));
        assertEquals(40, state.widthFor(EditorKind.PREVIEW));
        assertEquals(40 + EditorKind.CONSOLE.ordinal(), state.widthFor(EditorKind.CONSOLE));
        assertEquals(40 + EditorKind.UNTYPED.ordinal(), state.widthFor(EditorKind.UNTYPED));
    }

    @Test
    void loadStateValidatesButPreservesValidLegacyAndNewValues() {
        CodeScrollSettings settings = new CodeScrollSettings();
        CodeScrollSettings.State state = new CodeScrollSettings.State();
        state.fit = false;
        state.width = 180;
        state.diffWidth = 99;
        state.markerRegex = "NOTE-[0-9]+";
        state.viewportColor = "abcdef";
        settings.loadState(state);
        assertSame(state, settings.getState());
        assertFalse(settings.getState().fit);
        assertEquals(180, settings.getState().width);
        assertEquals(99, settings.getState().diffWidth);
        assertEquals("NOTE-[0-9]+", settings.getState().markerRegex);
        assertEquals("ABCDEF", settings.getState().viewportColor);
    }

    @Test
    void persistedXmlRetainsLegacyNamesAndRoundTripsNewSettings() {
        Element legacy = new Element("state")
                .addContent(new Element("option").setAttribute("name", "fit").setAttribute("value", "false"))
                .addContent(new Element("option").setAttribute("name", "width").setAttribute("value", "155"));
        CodeScrollSettings settings = new CodeScrollSettings();
        settings.loadState(XmlSerializer.deserialize(legacy, CodeScrollSettings.State.class));
        CodeScrollSettings.State state = settings.getState();
        assertFalse(state.fit);
        assertEquals(155, state.width);
        assertEquals(60, state.diffWidth);
        assertEquals(CodeScrollSettings.RenderStyle.CLEAN, state.renderStyle);
        state.fill = true;
        state.renderStyle = CodeScrollSettings.RenderStyle.ACCURATE;
        state.clickTiming = CodeScrollSettings.ClickTiming.UP;
        state.clickByMousePosition = true;
        state.diffWidth = 91;
        state.previewWidth = 92;
        state.consoleWidth = 93;
        state.untypedWidth = 94;
        state.hoverOnly = true;
        state.showQuickHide = false;
        state.hoverDelayMs = 450;
        state.emptyConsole = false;
        state.emptyUntyped = false;
        state.outOfRangeEmpty = true;
        state.viewportColor = "112233";
        state.viewportBorderColor = "445566";
        state.viewportOpacity = 50;
        state.viewportBorderOpacity = 100;
        state.viewportBorderWidth = 2;
        state.showBookmarks = false;
        state.showVcs = false;
        state.showDiagnostics = false;
        state.showSearch = false;
        state.fullLineDiagnostics = true;
        state.fullLineMarkup = true;
        state.methodNames = "load*\nsave";
        state.methodAnnotations = "Test,Jvm*";
        state.markerRegex = "NOTE-[0-9]+";
        state.markerFontSize = 14;
        state.showRenderedMarkdown = false;
        state.previewWheel = true;
        Element xml = XmlSerializer.serialize(state);
        settings.loadState(XmlSerializer.deserialize(xml, CodeScrollSettings.State.class));
        CodeScrollSettings.State restored = settings.getState();
        assertFalse(restored.fit);
        assertEquals(155, restored.width);
        assertTrue(restored.fill);
        assertEquals(CodeScrollSettings.RenderStyle.ACCURATE, restored.renderStyle);
        assertEquals(CodeScrollSettings.ClickTiming.UP, restored.clickTiming);
        assertTrue(restored.clickByMousePosition);
        assertEquals(91, restored.diffWidth);
        assertEquals(92, restored.previewWidth);
        assertEquals(93, restored.consoleWidth);
        assertEquals(94, restored.untypedWidth);
        assertTrue(restored.hoverOnly);
        assertFalse(restored.showQuickHide);
        assertEquals(450, restored.hoverDelayMs);
        assertFalse(restored.emptyConsole);
        assertFalse(restored.emptyUntyped);
        assertTrue(restored.outOfRangeEmpty);
        assertEquals("112233", restored.viewportColor);
        assertEquals("445566", restored.viewportBorderColor);
        assertEquals(50, restored.viewportOpacity);
        assertEquals(100, restored.viewportBorderOpacity);
        assertEquals(2, restored.viewportBorderWidth);
        assertFalse(restored.showBookmarks);
        assertFalse(restored.showVcs);
        assertFalse(restored.showDiagnostics);
        assertFalse(restored.showSearch);
        assertTrue(restored.fullLineDiagnostics);
        assertTrue(restored.fullLineMarkup);
        assertEquals("load*\nsave", restored.methodNames);
        assertEquals("Test,Jvm*", restored.methodAnnotations);
        assertEquals("NOTE-[0-9]+", restored.markerRegex);
        assertEquals(14, restored.markerFontSize);
        assertFalse(restored.showRenderedMarkdown);
        assertTrue(restored.previewWheel);
    }

    @Test
    void colorHelperNormalizesRgbClampsAlphaAndHandlesInvalidInput() {
        assertEquals(new Color(0xab, 0xc0, 0x12, 35), CodeScrollSettings.color(" #abc012 ", 35));
        assertEquals(0, CodeScrollSettings.color("000000", -10).getAlpha());
        assertEquals(255, CodeScrollSettings.color("FFFFFF", 999).getAlpha());
        assertEquals(new Color(0xa0, 0xa0, 0xa0, 110), CodeScrollSettings.color(null, 110));
        assertEquals(new Color(0xa0, 0xa0, 0xa0, 110), CodeScrollSettings.color("bad", 110));
    }
}

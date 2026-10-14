package dev.codescroll

import com.intellij.openapi.editor.EditorKind
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class CodeScrollSettingsTest {
    @Test
    fun defaults() {
        val state = CodeScrollSettings.State()
        assertFalse(state.fit)
        assertFalse(state.fill)
        assertEquals(110, state.width)
        assertEquals(3, state.pixelsPerLine)
        assertEquals(CodeScrollSettings.RenderStyle.CLEAN, state.renderStyle)
        assertEquals(CodeScrollSettings.ClickTiming.DOWN, state.clickTiming)
        assertEquals(listOf(60, 70, 60, 60), listOf(state.diffWidth, state.previewWidth, state.consoleWidth, state.untypedWidth))
        assertEquals(250, state.hoverDelayMs)
        assertTrue(state.showQuickHide && state.emptyConsole && state.emptyUntyped)
        assertFalse(state.hoverOnly || state.outOfRangeEmpty || state.clickByMousePosition)
        for (color in listOf(state.viewportColor, state.viewportBorderColor, state.caretLineColor, state.selectionColor,
            state.markerLabelColor, state.declarationLabelColor)) assertEquals("", color)
        assertEquals(70, state.textIntensity)
        assertEquals(38, state.viewportOpacity)
        assertEquals(0, state.viewportBorderWidth)
        assertTrue(state.showDiagnostics && state.showErrors && state.showWarnings && state.showVcs && state.showBookmarks && state.showSearch)
        assertEquals("", state.methodNames)
        assertTrue(state.showRenderedMarkdown && state.markdownSyncSource && state.previewLineNumbers)
        assertFalse(state.previewWheel)
    }

    @Test
    fun validationClampsEveryRangeAndRepairsColorsAndUnsafeRegex() {
        val state = CodeScrollSettings.State(
            width = Int.MIN_VALUE, diffWidth = Int.MAX_VALUE, previewWidth = 0, consoleWidth = 301, untypedWidth = -1,
            pixelsPerLine = 100, minLines = 2_000_000, maxLines = -1, debounceMs = 0, hoverDelayMs = -1,
            viewportOpacity = -1, viewportBorderOpacity = 256, viewportBorderWidth = -1, markerFontSize = 100,
            markerRegex = "(a+)+", viewportColor = " #aBc012 ", viewportBorderColor = "not a color",
            caretLineColor = "#12345", textIntensity = 500,
        )
        state.validate()
        assertEquals(listOf(40, 300, 40, 300, 40), listOf(state.width, state.diffWidth, state.previewWidth, state.consoleWidth, state.untypedWidth))
        assertEquals(8, state.pixelsPerLine)
        assertEquals(1_000_000, state.minLines)
        assertEquals(1_000_000, state.maxLines)
        assertEquals(50, state.debounceMs)
        assertEquals(0, state.hoverDelayMs)
        assertEquals(0, state.viewportOpacity)
        assertEquals(255, state.viewportBorderOpacity)
        assertEquals(0, state.viewportBorderWidth)
        assertEquals(36, state.markerFontSize)
        assertEquals("", state.markerRegex)
        assertEquals("ABC012", state.viewportColor)
        assertEquals("", state.viewportBorderColor)
        assertEquals("", state.caretLineColor)
        assertEquals(100, state.textIntensity)

        val low = CodeScrollSettings.State(minLines = -1, maxLines = Int.MAX_VALUE, pixelsPerLine = 0, debounceMs = 10_000,
            markerFontSize = 0, viewportBorderWidth = 100, hoverDelayMs = 3_000)
        low.validate()
        assertEquals(0, low.minLines)
        assertEquals(1_000_000, low.maxLines)
        assertEquals(1, low.pixelsPerLine)
        assertEquals(2_000, low.debounceMs)
        assertEquals(6, low.markerFontSize)
        assertEquals(8, low.viewportBorderWidth)
        assertEquals(2_000, low.hoverDelayMs)
    }

    @Test
    fun widthsAreIndependentForEveryEditorKindAndClampWhenSet() {
        val state = CodeScrollSettings.State()
        for (kind in EditorKind.entries) {
            state.setWidthFor(kind, 40 + kind.ordinal)
            assertEquals(40 + kind.ordinal, state.widthFor(kind))
        }
        val main = state.width
        state.setWidthFor(EditorKind.DIFF, 400)
        assertEquals(300, state.diffWidth)
        assertEquals(main, state.width)
        state.setWidthFor(EditorKind.PREVIEW, -1)
        assertEquals(40, state.previewWidth)
        state.setWidthFor(null, 125)
        assertEquals(125, state.widthFor(null))
        assertEquals(125, state.widthFor(EditorKind.MAIN_EDITOR))
    }

    @Test
    fun loadStateValidatesAndKeepsValidValues() {
        val settings = CodeScrollSettings()
        val state = CodeScrollSettings.State(width = 180, diffWidth = 99, markerRegex = "NOTE-[0-9]+", viewportColor = "abcdef")
        settings.loadState(state)
        assertSame(state, settings.state)
        assertEquals(180, settings.state.width)
        assertEquals(99, settings.state.diffWidth)
        assertEquals("NOTE-[0-9]+", settings.state.markerRegex)
        assertEquals("ABCDEF", settings.state.viewportColor)
    }

    @Test
    fun persistedXmlRoundTripsTheDataClassAndKeepsDefaultsForMissingOptions() {
        val legacy = Element("state")
            .addContent(Element("option").setAttribute("name", "fit").setAttribute("value", "true"))
            .addContent(Element("option").setAttribute("name", "width").setAttribute("value", "155"))
        val loaded = XmlSerializer.deserialize(legacy, CodeScrollSettings.State::class.java)
        assertTrue(loaded.fit)
        assertEquals(155, loaded.width)
        assertEquals(60, loaded.diffWidth)
        assertEquals(CodeScrollSettings.RenderStyle.CLEAN, loaded.renderStyle)

        val changed = loaded.copy(
            fill = true, renderStyle = CodeScrollSettings.RenderStyle.ACCURATE, clickTiming = CodeScrollSettings.ClickTiming.UP,
            diffWidth = 91, hoverOnly = true, viewportColor = "112233", showDiagnostics = false, showErrors = false,
            methodNames = "load*\nsave", markerRegex = "NOTE-[0-9]+", markerFontSize = 14, markdownSyncSource = false,
            previewLineNumbers = false,
        )
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(changed), CodeScrollSettings.State::class.java)
        assertEquals(changed, restored)
    }

    @Test
    fun colorHelperNormalizesRgbClampsAlphaAndFollowsTheSchemeWhenEmpty() {
        val scheme = Color(0x10, 0x20, 0x30)
        assertEquals(Color(0xab, 0xc0, 0x12, 35), CodeScrollSettings.color(" #abc012 ", scheme, 35))
        assertEquals(0, CodeScrollSettings.color("000000", scheme, -10).alpha)
        assertEquals(255, CodeScrollSettings.color("FFFFFF", scheme, 999).alpha)
        for (value in listOf(null, "", "bad")) assertEquals(Color(0x10, 0x20, 0x30, 110), CodeScrollSettings.color(value, scheme, 110))
        assertEquals("0A0B0C", CodeScrollSettings.hex(Color(10, 11, 12, 200)))
        assertEquals("", CodeScrollSettings.hex(null))
    }

    @Test
    fun statesCompareByEveryOption() {
        assertEquals(CodeScrollSettings.State(), CodeScrollSettings.State())
        assertNotEquals(CodeScrollSettings.State(), CodeScrollSettings.State(declarationLabelColor = "112233"))
        assertNotEquals(CodeScrollSettings.State(), CodeScrollSettings.State(textIntensity = 40))
    }
}

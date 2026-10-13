package dev.codescroll;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.editor.EditorKind;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Locale;

@Service(Service.Level.APP)
@com.intellij.openapi.components.State(name = "CodeScrollSettings", storages = @Storage("CodeScroll.xml"))
public final class CodeScrollSettings implements PersistentStateComponent<CodeScrollSettings.State> {
    public enum RenderStyle { CLEAN, ACCURATE }
    public enum ClickTiming { DOWN, UP, NONE }

    private State state = new State();

    public static CodeScrollSettings getInstance() {
        return ApplicationManager.getApplication().getService(CodeScrollSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        state.validate();
        this.state = state;
    }

    public static Color color(String hex, int opacity) {
        String normalized = normalizeColor(hex, "A0A0A0");
        return new Color((Integer.parseInt(normalized, 16) & 0xffffff)
                | (Math.clamp(opacity, 0, 255) << 24), true);
    }

    private static String normalizeColor(String value, String fallback) {
        if (value == null) return fallback;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        return hex.matches("[0-9a-fA-F]{6}") ? hex.toUpperCase(Locale.ROOT) : fallback;
    }

    public static final class State {
        public boolean enabled = true;
        public boolean leftAligned = false;
        public boolean fit = true;
        public boolean syntaxColors = true;
        public boolean showMarkers = true;
        public boolean showMethods = true;
        public boolean showPreview = true;
        public boolean scrollOnly = false;
        public boolean showMain = true;
        public boolean showDiff = true;
        public boolean showPreviewEditors = true;
        public boolean showConsole = false;
        public boolean showUntyped = false;
        public boolean hideScrollbar = false;
        public boolean autoWidth = true;
        public boolean lockWidth = false;
        public int width = 110;
        public int pixelsPerLine = 2;
        public int minLines = 0;
        public int maxLines = 1_000_000;
        public int debounceMs = 120;
        public String excludedExtensions = "ipynb";
        public String markerWords = "TODO,FIXME,MARK,region";
        public boolean fill = false;
        public RenderStyle renderStyle = RenderStyle.CLEAN;
        public ClickTiming clickTiming = ClickTiming.DOWN;
        public boolean clickByMousePosition = false;
        public int diffWidth = 60;
        public int previewWidth = 70;
        public int consoleWidth = 60;
        public int untypedWidth = 60;
        public boolean hoverOnly = false;
        public boolean showQuickHide = true;
        public int hoverDelayMs = 250;
        public boolean emptyConsole = true;
        public boolean emptyUntyped = true;
        public boolean outOfRangeEmpty = false;
        public String viewportColor = "A0A0A0";
        public String viewportBorderColor = "808080";
        public int viewportOpacity = 35;
        public int viewportBorderOpacity = 110;
        public int viewportBorderWidth = 1;
        public boolean showBookmarks = true;
        public boolean showVcs = true;
        public boolean showDiagnostics = true;
        public boolean showSearch = true;
        public boolean fullLineDiagnostics = false;
        public boolean fullLineMarkup = false;
        public String methodNames = "*";
        public String methodAnnotations = "";
        public String markerRegex = "";
        public int markerFontSize = 10;
        public boolean showRenderedMarkdown = true;
        public boolean previewWheel = false;

        public int widthFor(EditorKind kind) {
            if (kind == null) return width;
            return switch (kind) {
                case DIFF -> diffWidth;
                case PREVIEW -> previewWidth;
                case CONSOLE -> consoleWidth;
                case UNTYPED -> untypedWidth;
                default -> width;
            };
        }

        public void setWidthFor(EditorKind kind, int value) {
            value = Math.clamp(value, 40, 300);
            if (kind == null) {
                width = value;
                return;
            }
            switch (kind) {
                case DIFF -> diffWidth = value;
                case PREVIEW -> previewWidth = value;
                case CONSOLE -> consoleWidth = value;
                case UNTYPED -> untypedWidth = value;
                default -> width = value;
            }
        }

        public void validate() {
            if (excludedExtensions == null) excludedExtensions = "";
            if (markerWords == null) markerWords = "";
            if (renderStyle == null) renderStyle = RenderStyle.CLEAN;
            if (clickTiming == null) clickTiming = ClickTiming.DOWN;
            if (methodNames == null) methodNames = "*";
            if (methodAnnotations == null) methodAnnotations = "";
            if (markerRegex == null) markerRegex = "";
            try {
                MarkerScanner.compileMarkerRegex(markerRegex);
            } catch (IllegalArgumentException exception) {
                markerRegex = "";
            }
            viewportColor = normalizeColor(viewportColor, "A0A0A0");
            viewportBorderColor = normalizeColor(viewportBorderColor, "808080");
            width = Math.clamp(width, 40, 300);
            diffWidth = Math.clamp(diffWidth, 40, 300);
            previewWidth = Math.clamp(previewWidth, 40, 300);
            consoleWidth = Math.clamp(consoleWidth, 40, 300);
            untypedWidth = Math.clamp(untypedWidth, 40, 300);
            hoverDelayMs = Math.clamp(hoverDelayMs, 0, 2_000);
            viewportOpacity = Math.clamp(viewportOpacity, 0, 255);
            viewportBorderOpacity = Math.clamp(viewportBorderOpacity, 0, 255);
            viewportBorderWidth = Math.clamp(viewportBorderWidth, 0, 8);
            markerFontSize = Math.clamp(markerFontSize, 6, 36);
            pixelsPerLine = Math.clamp(pixelsPerLine, 1, 8);
            minLines = Math.clamp(minLines, 0, 1_000_000);
            maxLines = Math.clamp(maxLines, minLines, 1_000_000);
            debounceMs = Math.clamp(debounceMs, 50, 2_000);
        }
    }
}

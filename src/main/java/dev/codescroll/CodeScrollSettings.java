package dev.codescroll;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.APP)
@com.intellij.openapi.components.State(name = "CodeScrollSettings", storages = @Storage("CodeScroll.xml"))
public final class CodeScrollSettings implements PersistentStateComponent<CodeScrollSettings.State> {
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

        public void validate() {
            if (excludedExtensions == null) excludedExtensions = "";
            if (markerWords == null) markerWords = "";
            width = Math.clamp(width, 40, 300);
            pixelsPerLine = Math.clamp(pixelsPerLine, 1, 8);
            minLines = Math.clamp(minLines, 0, 1_000_000);
            maxLines = Math.clamp(maxLines, minLines, 1_000_000);
            debounceMs = Math.clamp(debounceMs, 50, 2_000);
        }
    }
}

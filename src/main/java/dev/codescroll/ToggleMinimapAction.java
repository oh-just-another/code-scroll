package dev.codescroll;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class ToggleMinimapAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        CodeScrollSettings.State state = CodeScrollSettings.getInstance().getState();
        state.enabled = !state.enabled;
        // Turning minimaps on again also restores editors hidden one by one.
        if (state.enabled) CodeScrollManager.getInstance().clearHidden();
        CodeScrollManager.getInstance().refreshAll();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        boolean enabled = CodeScrollSettings.getInstance().getState().enabled;
        event.getPresentation().setText(enabled ? "Disable Code Scroll Minimap" : "Enable Code Scroll Minimap");
    }
}

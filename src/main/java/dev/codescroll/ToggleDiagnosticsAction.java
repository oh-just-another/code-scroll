package dev.codescroll;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareToggleAction;
import org.jetbrains.annotations.NotNull;

/** Shows or hides error and warning marks on every minimap; no default shortcut. */
public final class ToggleDiagnosticsAction extends DumbAwareToggleAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent event) {
        return CodeScrollSettings.getInstance().getState().showDiagnostics;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent event, boolean state) {
        CodeScrollSettings.getInstance().getState().showDiagnostics = state;
        CodeScrollManager.getInstance().repaintAll();
    }
}

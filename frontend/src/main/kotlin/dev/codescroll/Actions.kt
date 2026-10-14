package dev.codescroll

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction

class ToggleMinimapAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val state = CodeScrollSettings.current
        state.enabled = !state.enabled
        // Turning minimaps on again also restores editors hidden one by one.
        if (state.enabled) CodeScrollManager.getInstance().clearHidden()
        CodeScrollManager.getInstance().refreshAll()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.text =
            if (CodeScrollSettings.current.enabled) "Disable Code Scroll Minimap" else "Enable Code Scroll Minimap"
    }
}

/** Shows or hides error and warning marks on every minimap; no default shortcut. */
class ToggleDiagnosticsAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(event: AnActionEvent) = CodeScrollSettings.current.showDiagnostics

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        CodeScrollSettings.current.showDiagnostics = state
        CodeScrollManager.getInstance().repaintAll()
    }
}

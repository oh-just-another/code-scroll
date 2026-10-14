// SPDX-License-Identifier: MIT
package dev.codescroll.markdown

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import dev.codescroll.CodeScrollConfigurable
import dev.codescroll.CodeScrollSettings

// Items of the Code Scroll submenu in the rendered Markdown preview's context menu. Open previews pick up the
// changed settings within a second, when their map next asks the IDE for its state.

class ToggleMarkdownSyncSourceAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(event: AnActionEvent) = CodeScrollSettings.current.markdownSyncSource
    override fun setSelected(event: AnActionEvent, state: Boolean) {
        CodeScrollSettings.current.markdownSyncSource = state
    }
}

class ToggleMinimapWidthLockAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(event: AnActionEvent) = CodeScrollSettings.current.lockWidth
    override fun setSelected(event: AnActionEvent, state: Boolean) {
        CodeScrollSettings.current.lockWidth = state
    }
}

class ToggleRenderedMarkdownMinimapAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(event: AnActionEvent) = CodeScrollSettings.current.showRenderedMarkdown
    override fun setSelected(event: AnActionEvent, state: Boolean) {
        CodeScrollSettings.current.showRenderedMarkdown = state
    }
}

class OpenCodeScrollSettingsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(event: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(event.project, CodeScrollConfigurable::class.java)
    }
}

package dev.codescroll

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CodeScrollEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) = CodeScrollManager.getInstance().attachLater(event.editor)
    override fun editorReleased(event: EditorFactoryEvent) = CodeScrollManager.getInstance().detach(event.editor)
}

/** Covers editors restored before the editor-factory listener saw them. Never recreates existing maps. */
class CodeScrollStartup : ProjectActivity {
    override suspend fun execute(project: Project) = CodeScrollManager.getInstance().attachExisting()
}

/** Startup activities do not run for already open projects when the plugin is installed without restart. */
class CodeScrollPluginListener : DynamicPluginListener {
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == "dev.codescroll") CodeScrollManager.getInstance().attachExisting()
    }
}

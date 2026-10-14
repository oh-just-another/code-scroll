// SPDX-License-Identifier: MIT
package dev.codescroll.markdown

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.codescroll.CodeScrollSettings
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import java.util.UUID

/** Uses the Markdown plugin's public (currently obsolete, but still registered) browser extension API. */
class RenderedMarkdownMinimapProvider : MarkdownBrowserPreviewExtension.Provider {
    override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension? {
        val state = CodeScrollSettings.current
        val pipe = panel.browserPipe
        if (!state.enabled || !state.showRenderedMarkdown || pipe == null) return null
        return Extension(pipe, panel)
    }

    private class Extension(
        private val pipe: BrowserPipe, private val panel: MarkdownHtmlPanel,
    ) : MarkdownBrowserPreviewExtension, ResourceProvider {
        private val id = UUID.randomUUID().toString()
        // A reload creates a new extension; its script must not reuse the previous instance's cached token.
        private val scriptName = "dev/codescroll/markdown/rendered-minimap-$id.js"
        @Volatile
        private var disposed = false
        private val handler = handler { data -> if (data == id) sendState() }

        /** "<id>:<width>", sent when the map's left edge has been dragged. */
        private val resized = handler { data ->
            val width = data.removePrefix("$id:").takeIf { it != data }?.toIntOrNull() ?: return@handler
            val state = CodeScrollSettings.current
            if (!state.lockWidth) state.markdownMapWidth = width.coerceIn(40, 300)
        }

        /** "<id>:<offset>", the source offset now at the top of the preview after map navigation. */
        private val source = handler { data ->
            val offset = data.removePrefix("$id:").takeIf { it != data }?.toIntOrNull() ?: return@handler
            ApplicationManager.getApplication().invokeLater { if (!disposed) scrollSource(offset) }
        }

        /**
         * Puts the line at [offset] at the top of every editor of the previewed file, without moving the caret.
         * The Markdown plugin no longer scrolls the source when the preview scrolls, so the map does it.
         */
        private fun scrollSource(offset: Int) {
            if (!CodeScrollSettings.current.markdownSyncSource) return
            val file = panel.virtualFile ?: return
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return
            val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
            for (editor in EditorFactory.getInstance().getEditors(document, panel.project)) {
                if (editor.isDisposed || editor.editorKind != EditorKind.MAIN_EDITOR) continue
                editor.scrollingModel.scrollVertically(editor.logicalPositionToXY(LogicalPosition(line, 0)).y)
            }
        }

        private fun handler(receive: (String) -> Unit) = object : BrowserPipe.Handler {
            override fun processMessageReceived(data: String): Boolean {
                if (!disposed) receive(data)
                return false
            }
        }

        init {
            pipe.subscribe(REQUEST, handler)
            pipe.subscribe(RESIZED, resized)
            pipe.subscribe(SOURCE, source)
        }

        /** Enabled state, source sync, width, text intensity and width lock, as the script parses them. */
        private fun sendState() {
            val state = CodeScrollSettings.current
            pipe.send(CONTROL, listOf(
                id, if (state.enabled && state.showRenderedMarkdown) "on" else "off",
                if (state.markdownSyncSource) "sync" else "nosync", state.markdownMapWidth, state.textIntensity,
                if (state.lockWidth) "locked" else "free",
            ).joinToString(":"))
        }

        override val priority get() = MarkdownBrowserPreviewExtension.Priority.DEFAULT
        override val scripts get() = listOf(scriptName)
        override val styles get() = emptyList<String>()
        override val resourceProvider: ResourceProvider get() = this

        override fun compareTo(other: MarkdownBrowserPreviewExtension): Int = other.priority.value.compareTo(priority.value)

        override fun canProvide(resourceName: String) = !disposed && resourceName == scriptName

        override fun loadResource(resourceName: String): ResourceProvider.Resource? {
            if (!canProvide(resourceName)) return null
            val script = ResourceProvider.loadInternalResource(
                RenderedMarkdownMinimapProvider::class.java, "/$SCRIPT", JAVASCRIPT,
            ) ?: error("Missing bundled Markdown minimap script")
            val source = "window.__codeScrollMarkdownExtensionId = '$id';\n" + String(script.content, Charsets.UTF_8)
            return ResourceProvider.Resource(source.toByteArray(Charsets.UTF_8), JAVASCRIPT)
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            pipe.removeSubscription(REQUEST, handler)
            pipe.removeSubscription(RESIZED, resized)
            pipe.removeSubscription(SOURCE, source)
            pipe.send(CONTROL, "$id:dispose")
        }

        private companion object {
            const val SCRIPT = "dev/codescroll/markdown/rendered-minimap.js"
            const val REQUEST = "code-scroll.markdown.state-request"
            const val CONTROL = "code-scroll.markdown.control"
            const val RESIZED = "code-scroll.markdown.resized"
            const val SOURCE = "code-scroll.markdown.source"
            const val JAVASCRIPT = "application/javascript; charset=utf-8"
        }
    }
}

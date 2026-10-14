// SPDX-License-Identifier: MIT
package dev.codescroll.markdown

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
        return Extension(pipe)
    }

    private class Extension(private val pipe: BrowserPipe) : MarkdownBrowserPreviewExtension, ResourceProvider {
        private val id = UUID.randomUUID().toString()
        // A reload creates a new extension; its script must not reuse the previous instance's cached token.
        private val scriptName = "dev/codescroll/markdown/rendered-minimap-$id.js"
        @Volatile
        private var disposed = false
        private val handler = object : BrowserPipe.Handler {
            override fun processMessageReceived(data: String): Boolean {
                if (!disposed && data == id) {
                    val state = CodeScrollSettings.current
                    pipe.send(CONTROL, id + (if (state.enabled && state.showRenderedMarkdown) ":on" else ":off") +
                        if (state.markdownSyncSource) ":sync" else ":nosync")
                }
                return false
            }
        }

        init {
            pipe.subscribe(REQUEST, handler)
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
            pipe.send(CONTROL, "$id:dispose")
        }

        private companion object {
            const val SCRIPT = "dev/codescroll/markdown/rendered-minimap.js"
            const val REQUEST = "code-scroll.markdown.state-request"
            const val CONTROL = "code-scroll.markdown.control"
            const val JAVASCRIPT = "application/javascript; charset=utf-8"
        }
    }
}

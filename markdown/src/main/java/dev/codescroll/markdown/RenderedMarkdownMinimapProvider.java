// SPDX-License-Identifier: MIT
package dev.codescroll.markdown;

import dev.codescroll.CodeScrollSettings;
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension;
import org.intellij.plugins.markdown.ui.preview.BrowserPipe;
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel;
import org.intellij.plugins.markdown.ui.preview.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Uses the Markdown plugin's public (currently obsolete, but still registered) browser extension API. */
public final class RenderedMarkdownMinimapProvider implements MarkdownBrowserPreviewExtension.Provider {
    @Override
    public @Nullable MarkdownBrowserPreviewExtension createBrowserExtension(@NotNull MarkdownHtmlPanel panel) {
        CodeScrollSettings.State state = CodeScrollSettings.getInstance().getState();
        BrowserPipe pipe = panel.getBrowserPipe();
        if (!state.enabled || !state.showRenderedMarkdown || pipe == null) return null;
        return new Extension(pipe);
    }

    private static final class Extension implements MarkdownBrowserPreviewExtension, ResourceProvider {
        private static final String SCRIPT = "dev/codescroll/markdown/rendered-minimap.js";
        private static final String REQUEST = "code-scroll.markdown.state-request";
        private static final String CONTROL = "code-scroll.markdown.control";
        private final String id = UUID.randomUUID().toString();
        // A reload creates a new extension; its script must not reuse the previous instance's cached token.
        private final String scriptName = "dev/codescroll/markdown/rendered-minimap-" + id + ".js";
        private final BrowserPipe pipe;
        private final BrowserPipe.Handler handler;
        private volatile boolean disposed;

        private Extension(BrowserPipe pipe) {
            this.pipe = pipe;
            handler = data -> {
                if (!disposed && id.equals(data)) {
                    CodeScrollSettings.State state = CodeScrollSettings.getInstance().getState();
                    pipe.send(CONTROL, id + (state.enabled && state.showRenderedMarkdown ? ":on" : ":off"));
                }
                return false;
            };
            pipe.subscribe(REQUEST, handler);
        }

        @Override public @NotNull Priority getPriority() { return Priority.DEFAULT; }
        @Override public @NotNull List<String> getScripts() { return List.of(scriptName); }
        @Override public @NotNull List<String> getStyles() { return List.of(); }
        @Override public @NotNull ResourceProvider getResourceProvider() { return this; }
        @Override public int compareTo(@NotNull MarkdownBrowserPreviewExtension other) {
            return Integer.compare(other.getPriority().getValue(), getPriority().getValue());
        }
        @Override public boolean canProvide(@NotNull String resourceName) {
            return !disposed && scriptName.equals(resourceName);
        }

        @Override public @Nullable Resource loadResource(@NotNull String resourceName) {
            if (!canProvide(resourceName)) return null;
            Resource script = ResourceProvider.loadInternalResource(
                    RenderedMarkdownMinimapProvider.class, "/" + SCRIPT, "application/javascript; charset=utf-8");
            if (script == null) throw new IllegalStateException("Missing bundled Markdown minimap script");
            String source = "window.__codeScrollMarkdownExtensionId = '" + id + "';\n"
                    + new String(script.getContent(), StandardCharsets.UTF_8);
            return new Resource(source.getBytes(StandardCharsets.UTF_8), "application/javascript; charset=utf-8");
        }

        @Override public void dispose() {
            if (disposed) return;
            disposed = true;
            pipe.removeSubscription(REQUEST, handler);
            pipe.send(CONTROL, id + ":dispose");
        }
    }
}

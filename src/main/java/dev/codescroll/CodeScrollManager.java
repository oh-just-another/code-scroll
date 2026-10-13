package dev.codescroll;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.BorderLayout;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service(Service.Level.APP)
public final class CodeScrollManager implements Disposable {
    private final Map<Editor, MinimapPanel> panels = new HashMap<>();
    private boolean disposed;

    public static CodeScrollManager getInstance() {
        return ApplicationManager.getApplication().getService(CodeScrollManager.class);
    }

    public void attachLater(Editor editor) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !editor.isDisposed()) attach(editor);
        });
    }

    private void attach(Editor editor) {
        if (panels.containsKey(editor) || !eligible(editor)) return;
        // No side-panel API exists. Do not replace components owned by the IDE or other plugins.
        if (!(editor.getComponent().getLayout() instanceof BorderLayout layout)) return;
        var state = CodeScrollSettings.getInstance().getState();
        String side = state.leftAligned ? BorderLayout.LINE_START : BorderLayout.LINE_END;
        String absoluteSide = state.leftAligned ? BorderLayout.WEST : BorderLayout.EAST;
        if (layout.getLayoutComponent(side) != null || layout.getLayoutComponent(absoluteSide) != null) return;
        var panel = new MinimapPanel(editor);
        panels.put(editor, panel);
        Disposer.register(this, panel);
        editor.getComponent().add(panel, side);
        editor.getComponent().revalidate();
        editor.getComponent().repaint();
    }

    private boolean eligible(Editor editor) {
        var s = CodeScrollSettings.getInstance().getState();
        if (!s.enabled || editor.isDisposed() || editor.isOneLineMode()) return false;
        boolean kind = switch (editor.getEditorKind()) {
            case MAIN_EDITOR -> s.showMain;
            case DIFF -> s.showDiff;
            case PREVIEW -> s.showPreviewEditors;
            case CONSOLE -> s.showConsole;
            case UNTYPED -> s.showUntyped;
        };
        if (!kind) return false;
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file != null && file.getExtension() != null) {
            String extension = file.getExtension().toLowerCase(Locale.ROOT);
            for (String excluded : s.excludedExtensions.split(",")) {
                if (excluded.strip().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "").equals(extension)) return false;
            }
        }
        return true;
    }

    public void detach(Editor editor) {
        MinimapPanel panel = panels.remove(editor);
        if (panel != null) {
            Disposer.dispose(panel);
            editor.getComponent().remove(panel);
            editor.getComponent().revalidate();
            editor.getComponent().repaint();
        }
    }

    public void refreshAll() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) return;
            for (Editor editor : panels.keySet().toArray(Editor[]::new)) detach(editor);
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (!editor.isDisposed()) attach(editor);
            }
        });
    }

    @Override public void dispose() {
        disposed = true;
        // Child panels remove their own listeners and UI, including on dynamic plugin unload.
        panels.clear();
    }
}

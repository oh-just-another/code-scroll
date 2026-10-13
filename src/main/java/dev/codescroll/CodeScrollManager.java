package dev.codescroll;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.BorderLayout;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

@Service(Service.Level.APP)
public final class CodeScrollManager implements Disposable {
    private final Map<Editor, MinimapPanel> panels = new HashMap<>();
    /** Editors hidden with "Hide in This Editor"; weak so released editors are not retained. */
    private final Set<Editor> suppressed = Collections.newSetFromMap(new WeakHashMap<>());
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
        if (!s.enabled || editor.isDisposed() || editor.isOneLineMode() || suppressed.contains(editor)) return false;
        if (Boolean.TRUE.equals(editor.getUserData(PreviewPopup.PREVIEW_EDITOR))) return false;
        if (!showsKind(s, editor.getEditorKind())) return false;
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file != null && file.getExtension() != null) {
            String extension = file.getExtension().toLowerCase(Locale.ROOT);
            for (String excluded : s.excludedExtensions.split(",")) {
                if (excluded.strip().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "").equals(extension)) return false;
            }
        }
        return true;
    }

    /** Not an exhaustive switch: a future EditorKind constant must not throw MatchException at runtime. */
    static boolean showsKind(CodeScrollSettings.State s, EditorKind kind) {
        if (kind == EditorKind.MAIN_EDITOR) return s.showMain;
        if (kind == EditorKind.DIFF) return s.showDiff;
        if (kind == EditorKind.PREVIEW) return s.showPreviewEditors;
        if (kind == EditorKind.CONSOLE) return s.showConsole;
        if (kind == EditorKind.UNTYPED) return s.showUntyped;
        return false;
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

    /** Hides the minimap in one editor until minimaps are toggled on again. */
    public void hide(Editor editor) {
        suppressed.add(editor);
        detach(editor);
    }

    public void clearHidden() {
        suppressed.clear();
    }

    /** Attaches to editors that do not have a minimap yet, without recreating existing ones. */
    public void attachExisting() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) return;
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (!editor.isDisposed()) attach(editor);
            }
        });
    }

    /** Recreates every minimap; needed when alignment, eligibility or global switches change. */
    public void refreshAll() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) return;
            for (Editor editor : panels.keySet().toArray(Editor[]::new)) detach(editor);
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (!editor.isDisposed()) attach(editor);
            }
        });
    }

    /** Applies a width change to every minimap in place. */
    public void relayoutAll() {
        for (MinimapPanel panel : panels.values()) panel.updateWidth();
    }

    @Override public void dispose() {
        disposed = true;
        // Child panels remove their own listeners and UI, including on dynamic plugin unload.
        panels.clear();
        suppressed.clear();
    }
}

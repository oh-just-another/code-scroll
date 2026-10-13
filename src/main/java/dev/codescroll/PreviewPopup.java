package dev.codescroll;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.awt.RelativePoint;

import javax.swing.JComponent;
import javax.swing.ScrollPaneConstants;
import java.awt.Dimension;
import java.awt.Point;

/** Hover preview backed by a read-only viewer of the same document, so it uses real editor colors. */
final class PreviewPopup implements Disposable {
    /** Marks viewers created here so the minimap manager never attaches a minimap to them. */
    static final Key<Boolean> PREVIEW_EDITOR = Key.create("dev.codescroll.previewEditor");
    private static final int LINES = 15;
    /** Building a full lexer highlighter for a huge document would block the EDT. */
    private static final int MAX_HIGHLIGHTED_LENGTH = 2 * 1024 * 1024;

    private final Editor owner;
    private EditorEx viewer;
    private JBPopup popup;
    private int line = -1;

    PreviewPopup(Editor owner) {
        this.owner = owner;
    }

    boolean isShowing() {
        return popup != null && popup.isVisible();
    }

    int line() {
        return line;
    }

    /** Shows or moves the preview beside {@code anchor}, centered on {@code line}. */
    void show(JComponent anchor, boolean leftAligned, int mouseY, int line) {
        if (owner.isDisposed() || !anchor.isShowing()) return;
        EditorEx viewer = viewer();
        int lineHeight = viewer.getLineHeight();
        int width = Math.clamp(owner.getScrollingModel().getVisibleArea().width * 3 / 5, 280, 720);
        int height = LINES * lineHeight + 4;
        JComponent component = viewer.getComponent();
        component.setPreferredSize(new Dimension(width, height));
        int y = Math.clamp(mouseY - height / 2, 0, Math.max(0, anchor.getHeight() - height));
        Point point = new Point(leftAligned ? anchor.getWidth() + 2 : -width - 2, y);
        if (popup == null || !popup.isVisible()) {
            if (popup != null) popup.cancel();
            popup = JBPopupFactory.getInstance().createComponentPopupBuilder(component, null)
                    .setFocusable(false).setRequestFocus(false).setResizable(false).setMovable(false)
                    .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true)
                    .createPopup();
            popup.show(new RelativePoint(anchor, point));
        } else {
            popup.setLocation(new RelativePoint(anchor, point).getScreenPoint());
        }
        scrollTo(line);
    }

    void scrollTo(int line) {
        if (viewer == null || viewer.isDisposed()) return;
        int bounded = Math.clamp(line, 0, Math.max(0, viewer.getDocument().getLineCount() - 1));
        this.line = bounded;
        viewer.getCaretModel().moveToLogicalPosition(new LogicalPosition(bounded, 0));
        int y = viewer.logicalPositionToXY(new LogicalPosition(bounded, 0)).y;
        viewer.getScrollingModel().disableAnimation();
        try {
            viewer.getScrollingModel().scrollVertically(Math.max(0, y - LINES / 2 * viewer.getLineHeight()));
        } finally {
            viewer.getScrollingModel().enableAnimation();
        }
    }

    void hide() {
        if (popup != null) popup.cancel();
        popup = null;
        line = -1;
    }

    private EditorEx viewer() {
        if (viewer != null && !viewer.isDisposed()) return viewer;
        var document = owner.getDocument();
        viewer = (EditorEx) EditorFactory.getInstance().createViewer(document, owner.getProject(), EditorKind.PREVIEW);
        // Set synchronously: the minimap manager attaches only later, on the next EDT event.
        viewer.putUserData(PREVIEW_EDITOR, Boolean.TRUE);
        viewer.setColorsScheme(owner.getColorsScheme());
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file != null && document.getTextLength() <= MAX_HIGHLIGHTED_LENGTH) {
            viewer.setHighlighter(EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(owner.getProject(), file));
        }
        var settings = viewer.getSettings();
        settings.setLineNumbersShown(true);
        settings.setFoldingOutlineShown(false);
        settings.setLineMarkerAreaShown(false);
        settings.setIndentGuidesShown(false);
        settings.setAdditionalLinesCount(0);
        settings.setAdditionalColumnsCount(0);
        settings.setCaretRowShown(true);
        settings.setUseSoftWraps(false);
        settings.setRightMarginShown(false);
        viewer.setCaretVisible(false);
        viewer.getScrollPane().setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        viewer.getScrollPane().setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return viewer;
    }

    @Override public void dispose() {
        hide();
        if (viewer != null && !viewer.isDisposed()) EditorFactory.getInstance().releaseEditor(viewer);
        viewer = null;
    }
}

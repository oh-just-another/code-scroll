package dev.codescroll;

import com.intellij.codeInsight.hint.EditorFragmentComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Key;
import com.intellij.ui.awt.RelativePoint;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Point;

/**
 * Hover preview built from the platform's editor fragment component, the same one the IDE shows for
 * error-stripe marks: it paints the real editor, so colors, folding and line numbers are exact.
 */
final class PreviewPopup implements Disposable {
    /** Kept for editors created by older builds; the fragment component creates no editor of its own. */
    static final Key<Boolean> PREVIEW_EDITOR = Key.create("dev.codescroll.previewEditor");
    /** Lines shown above and below the hovered line. */
    private static final int CONTEXT = 7;

    private final Editor owner;
    private final JPanel content = new JPanel(new BorderLayout());
    private JBPopup popup;
    private int line = -1;
    private JComponent anchor;
    private boolean leftAligned;
    private int mouseY;

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
        this.anchor = anchor;
        this.leftAligned = leftAligned;
        this.mouseY = mouseY;
        if (line != this.line || !isShowing()) render(line);
    }

    void scrollTo(int line) {
        if (anchor != null && isShowing()) render(line);
    }

    private void render(int target) {
        int lastLine = Math.max(0, owner.getDocument().getLineCount() - 1);
        line = Math.clamp(target, 0, lastLine);
        int start = Math.max(0, line - CONTEXT);
        int end = Math.min(lastLine, line + CONTEXT);
        boolean gutter = CodeScrollSettings.getInstance().getState().previewLineNumbers;
        JComponent fragment = EditorFragmentComponent.createEditorFragmentComponent(owner, start, end + 1, true, gutter);
        content.removeAll();
        content.add(fragment, BorderLayout.CENTER);
        Dimension size = fragment.getPreferredSize();
        int width = Math.min(size.width, Math.max(280, owner.getScrollingModel().getVisibleArea().width * 3 / 5));
        size = new Dimension(width, size.height);
        content.setPreferredSize(size);
        int y = Math.clamp(mouseY - size.height / 2, 0, Math.max(0, anchor.getHeight() - size.height));
        Point point = new Point(leftAligned ? anchor.getWidth() + 2 : -size.width - 2, y);
        if (!isShowing()) {
            if (popup != null) popup.cancel();
            popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
                    .setFocusable(false).setRequestFocus(false).setResizable(false).setMovable(false)
                    .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true)
                    .createPopup();
            popup.show(new RelativePoint(anchor, point));
        } else {
            popup.setSize(size);
            popup.setLocation(new RelativePoint(anchor, point).getScreenPoint());
            content.revalidate();
            content.repaint();
        }
    }

    void hide() {
        if (popup != null) popup.cancel();
        popup = null;
        line = -1;
    }

    @Override public void dispose() {
        hide();
        content.removeAll();
    }
}

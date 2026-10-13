package dev.codescroll;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

public final class MinimapPanel extends JPanel implements Disposable {
    private record Label(int line, String text) {}
    private record Frame(BufferedImage image, MapGeometry geometry, List<Label> labels, long stamp) {}
    private final Editor editor;
    private final Timer debounce;
    private final Timer appearance;
    private Future<?> renderTask;
    private Frame frame;
    private long generation;
    private boolean disposed;
    private boolean dragging;
    private boolean resizing;
    private double dragOffset;
    private MapGeometry dragGeometry;
    private int dragPixelOffset;
    private int initialX;
    private int initialWidth;
    private int oldScrollbarPolicy = -1;
    private boolean scrollbarHidden;
    private Color previousBackground;
    private Color previousForeground;
    private EditorColorsScheme previousScheme;
    private final ComponentAdapter editorResize;

    public MinimapPanel(Editor editor) {
        this.editor = editor;
        setOpaque(true);
        setFocusable(false);
        setToolTipText("");
        updateWidth();
        debounce = new Timer(settings().debounceMs, event -> submitRender());
        debounce.setRepeats(false);
        // Theme and markup changes do not necessarily generate document events.
        appearance = new Timer(500, event -> {
            if (disposed || editor.isDisposed() || !isShowing()) return;
            Color background = editor.getColorsScheme().getDefaultBackground();
            Color foreground = editor.getColorsScheme().getDefaultForeground();
            if (editor.getColorsScheme() != previousScheme || !background.equals(previousBackground)
                    || !foreground.equals(previousForeground)) invalidateMap();
            repaint();
        });
        appearance.start();
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) { invalidateMap(); }
        }, this);
        editor.getScrollingModel().addVisibleAreaListener(event -> {
            if (!settings().fit && !dragging) invalidateMap();
            repaint();
        }, this);
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override public void caretPositionChanged(CaretEvent event) { repaint(); }
        }, this);
        editor.getSelectionModel().addSelectionListener(new SelectionListener() {
            @Override public void selectionChanged(SelectionEvent event) { repaint(); }
        }, this);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                updateWidth();
                invalidateMap();
            }
            @Override public void componentShown(ComponentEvent event) { invalidateMap(); }
            @Override public void componentHidden(ComponentEvent event) {
                generation++;
                debounce.stop();
                if (renderTask != null) renderTask.cancel(true);
                frame = null;
            }
        });
        editorResize = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateWidth(); }
        };
        editor.getComponent().addComponentListener(editorResize);
        var mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (popup(event)) return;
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                initialX = event.getXOnScreen();
                initialWidth = settings().width;
                resizing = !settings().lockWidth && (settings().leftAligned
                        ? event.getX() > getWidth() - 5 : event.getX() < 5);
                if (resizing) return;
                MapGeometry geometry = geometry();
                Rectangle viewport = viewport(geometry);
                dragging = viewport.contains(event.getPoint()) && !event.isShiftDown();
                dragOffset = event.getY() - geometry.y(visibleStart());
                dragGeometry = geometry;
                dragPixelOffset = editor.getScrollingModel().getVisibleArea().y
                        - editor.logicalPositionToXY(new LogicalPosition(visibleStart(), 0)).y;
                if (!dragging) navigate(geometry.line(event.getY()), true, event.isShiftDown());
            }
            @Override public void mouseReleased(MouseEvent event) {
                if (resizing) CodeScrollManager.getInstance().refreshAll();
                resizing = dragging = false;
                dragGeometry = null;
                if (!settings().fit) invalidateMap();
                popup(event);
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (resizing) {
                    int delta = event.getXOnScreen() - initialX;
                    settings().width = Math.clamp(initialWidth + (settings().leftAligned ? delta : -delta), 40, 300);
                    updateWidth();
                    invalidateMap();
                } else if (dragging) {
                    navigate(dragGeometry.line(event.getY() - dragOffset), false, false);
                }
            }
            @Override public void mouseMoved(MouseEvent event) {
                boolean edge = !settings().lockWidth && (settings().leftAligned
                        ? event.getX() > getWidth() - 5 : event.getX() < 5);
                setCursor(Cursor.getPredefinedCursor(edge ? Cursor.E_RESIZE_CURSOR : Cursor.HAND_CURSOR));
            }
            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                editor.getScrollingModel().scrollVertically(Math.max(0,
                        editor.getScrollingModel().getVerticalScrollOffset()
                                + (int) (event.getPreciseWheelRotation() * editor.getLineHeight() * 3)));
                event.consume();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        if (settings().hideScrollbar && editor instanceof EditorEx ex) {
            oldScrollbarPolicy = ex.getScrollPane().getVerticalScrollBarPolicy();
        }
        invalidateMap();
    }

    private CodeScrollSettings.State settings() { return CodeScrollSettings.getInstance().getState(); }

    private void updateWidth() {
        int width = settings().width;
        if (settings().autoWidth && !resizing && editor.getComponent().getWidth() > 0) {
            width = Math.min(width, Math.max(40, editor.getComponent().getWidth() / 5));
        }
        if (getPreferredSize().width != width) {
            setPreferredSize(new Dimension(width, 0));
            revalidate();
        }
    }

    private int visibleStart() {
        return editor.xyToLogicalPosition(new Point(0, editor.getScrollingModel().getVisibleArea().y)).line;
    }

    private int visibleEnd() {
        Rectangle area = editor.getScrollingModel().getVisibleArea();
        return Math.min(editor.getDocument().getLineCount(),
                editor.xyToLogicalPosition(new Point(0, area.y + area.height)).line + 1);
    }

    private MapGeometry geometry() {
        if (dragging && dragGeometry != null) return dragGeometry;
        if (frame != null) return frame.geometry();
        return MapGeometry.create(editor.getDocument().getLineCount(), getHeight(), settings().fit,
                settings().pixelsPerLine, visibleStart(), visibleEnd());
    }

    private void invalidateMap() {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::invalidateMap);
            return;
        }
        if (disposed || editor.isDisposed()) return;
        int lines = editor.getDocument().getLineCount();
        boolean allowed = lines >= settings().minLines && lines <= settings().maxLines;
        if (!allowed) frame = null;
        if (oldScrollbarPolicy != -1 && editor instanceof EditorEx ex && scrollbarHidden != allowed) {
            ex.getScrollPane().setVerticalScrollBarPolicy(allowed
                    ? ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER : oldScrollbarPolicy);
            scrollbarHidden = allowed;
        }
        if (isVisible() != allowed) {
            setVisible(allowed);
            editor.getComponent().revalidate();
        }
        generation++;
        if (renderTask != null) renderTask.cancel(true);
        debounce.setInitialDelay(settings().debounceMs);
        debounce.restart();
        repaint();
    }

    private void submitRender() {
        if (disposed || editor.isDisposed() || getWidth() <= 0 || getHeight() <= 0 || !isShowing()) return;
        if (dragging && !settings().fit) return;
        var s = settings();
        int lines = editor.getDocument().getLineCount();
        if (lines < s.minLines || lines > s.maxLines) {
            frame = null;
            repaint();
            return;
        }
        MapGeometry geometry = MapGeometry.create(lines, getHeight(), s.fit, s.pixelsPerLine,
                visibleStart(), visibleEnd());
        int width = Math.min(RasterRenderer.MAX_WIDTH, getWidth());
        previousScheme = editor.getColorsScheme();
        EditorColorsScheme scheme = (EditorColorsScheme) editor.getColorsScheme().clone();
        previousBackground = scheme.getDefaultBackground();
        previousForeground = scheme.getDefaultForeground();
        boolean syntax = s.syntaxColors;
        boolean markers = s.showMarkers;
        boolean methods = s.showMethods;
        String words = s.markerWords;
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        long request = generation;
        renderTask = ReadAction.nonBlocking(() -> {
            var document = editor.getDocument();
            CharSequence text = document.getImmutableCharSequence();
            long stamp = document.getModificationStamp();
            int capacity = geometry.height() * 3;
            int[] starts = new int[capacity];
            int[] ends = new int[capacity];
            int[] rows = new int[capacity];
            int samples = 0;
            for (int y = 0; y < geometry.height(); y++) {
                if ((y & 63) == 0) ProgressManager.checkCanceled();
                if (y + geometry.origin() >= geometry.lines() * geometry.scale()) break;
                int first = geometry.line(y);
                int last = geometry.line(y + 0.999);
                int[] candidates = { first, (first + last) / 2, last };
                int previous = -1;
                for (int line : candidates) {
                    if (line == previous) continue;
                    previous = line;
                    // A write action cancels this read before stale line offsets are published.
                    if (line >= document.getLineCount()) continue;
                    starts[samples] = document.getLineStartOffset(line);
                    ends[samples] = document.getLineEndOffset(line);
                    rows[samples++] = y;
                }
            }
            List<RasterRenderer.Token> tokens = new ArrayList<>();
            // Huge documents retain navigation and structure without a full lexical pass.
            if (syntax && text.length() <= 1_048_576 && file != null) {
                SyntaxHighlighter highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(
                        file.getFileType(), editor.getProject(), file);
                if (highlighter != null) {
                    Lexer lexer = highlighter.getHighlightingLexer();
                    lexer.start(text);
                    long deadline = System.nanoTime() + 40_000_000;
                    while (lexer.getTokenType() != null && tokens.size() < 50_000) {
                        if ((tokens.size() & 127) == 0) {
                            ProgressManager.checkCanceled();
                            if (System.nanoTime() >= deadline) break;
                        }
                        Color color = scheme.getDefaultForeground();
                        for (var key : highlighter.getTokenHighlights(lexer.getTokenType())) {
                            var attributes = scheme.getAttributes(key);
                            if (attributes != null && attributes.getForegroundColor() != null) color = attributes.getForegroundColor();
                        }
                        tokens.add(new RasterRenderer.Token(lexer.getTokenStart(), lexer.getTokenEnd(), color.getRGB()));
                        lexer.advance();
                    }
                }
            }
            BufferedImage image = RasterRenderer.render(text, width, geometry.height(),
                    java.util.Arrays.copyOf(starts, samples), java.util.Arrays.copyOf(ends, samples),
                    java.util.Arrays.copyOf(rows, samples), scheme.getDefaultForeground().getRGB(),
                    scheme.getDefaultBackground().getRGB(), tokens, ProgressManager::checkCanceled);
            List<Label> labels = new ArrayList<>();
            if (markers) {
                for (var marker : MarkerScanner.scan(text, words, methods, 2000, ProgressManager::checkCanceled)) {
                    labels.add(new Label(document.getLineNumber(marker.offset()), marker.label()));
                }
            }
            return new Frame(image, geometry, List.copyOf(labels), stamp);
        }).expireWith(this).coalesceBy(this)
                .finishOnUiThread(ModalityState.any(), result -> {
                    if (disposed || editor.isDisposed() || request != generation
                            || result.stamp() != editor.getDocument().getModificationStamp()) return;
                    frame = result;
                    repaint();
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (disposed || editor.isDisposed()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Color background = editor.getColorsScheme().getDefaultBackground();
            g.setColor(background);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (frame != null) g.drawImage(frame.image(), 0, 0, null);
            MapGeometry geometry = geometry();
            Color accent = editor.getColorsScheme().getColor(EditorColors.CARET_COLOR);
            if (accent == null) accent = editor.getColorsScheme().getDefaultForeground();
            Rectangle viewport = viewport(geometry);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 28));
            g.fill(viewport);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 110));
            g.drawRect(viewport.x, viewport.y, Math.max(0, viewport.width - 1), Math.max(0, viewport.height - 1));
            var selection = editor.getSelectionModel();
            if (selection.hasSelection()) {
                int top = editor.getDocument().getLineNumber(selection.getSelectionStart());
                int bottom = editor.getDocument().getLineNumber(selection.getSelectionEnd()) + 1;
                g.setColor(new Color(70, 135, 230, 70));
                band(g, geometry, top, bottom, 0, getWidth());
            }
            paintMarkup(g, geometry, editor.getMarkupModel());
            if (editor instanceof EditorEx ex) paintMarkup(g, geometry, ex.getFilteredDocumentMarkupModel());
            g.setColor(accent);
            band(g, geometry, editor.getCaretModel().getLogicalPosition().line,
                    editor.getCaretModel().getLogicalPosition().line + 1, 0, getWidth());
            if (frame != null && settings().showMarkers) {
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                int previousBottom = -1;
                for (Label label : frame.labels()) {
                    int y = (int) geometry.y(label.line());
                    if (y < 0 || y >= getHeight() || y < previousBottom) continue;
                    g.setColor(new Color(background.getRed(), background.getGreen(), background.getBlue(), 225));
                    g.fillRect(3, y, getWidth() - 9, 12);
                    g.setColor(accent);
                    g.drawString(label.text(), 4, y + 10);
                    previousBottom = y + 13;
                }
            }
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 55));
            int edge = settings().leftAligned ? getWidth() - 1 : 0;
            g.drawLine(edge, 0, edge, getHeight());
        } finally { g.dispose(); }
    }

    private Rectangle viewport(MapGeometry geometry) {
        int top = (int) Math.max(0, geometry.y(visibleStart()));
        int bottom = (int) Math.min(getHeight(), Math.ceil(geometry.y(visibleEnd())));
        return new Rectangle(0, top, getWidth(), Math.max(4, bottom - top));
    }

    private void paintMarkup(Graphics2D g, MapGeometry geometry, MarkupModel model) {
        if (!(model instanceof MarkupModelEx extended)) return;
        int topLine = geometry.line(0);
        int bottomLine = geometry.line(getHeight());
        var document = editor.getDocument();
        int start = document.getLineStartOffset(Math.min(document.getLineCount() - 1, topLine));
        int end = document.getLineEndOffset(Math.min(document.getLineCount() - 1, bottomLine));
        long deadline = System.nanoTime() + 2_000_000;
        int painted = 0;
        try (var iterator = extended.overlappingIterator(start, end)) {
          while (iterator.hasNext() && painted < 2000 && System.nanoTime() < deadline) {
            RangeHighlighter highlighter = iterator.next();
            if (!highlighter.isValid()) continue;
            Color color = highlighter.getErrorStripeMarkColor(editor.getColorsScheme());
            var attributes = highlighter.getTextAttributes(editor.getColorsScheme());
            if (color == null && attributes != null) {
                color = attributes.getBackgroundColor();
            }
            if (color == null) continue;
            int top = document.getLineNumber(Math.min(document.getTextLength(), highlighter.getStartOffset()));
            int bottom = document.getLineNumber(Math.min(document.getTextLength(), highlighter.getEndOffset())) + 1;
            g.setColor(color);
            band(g, geometry, top, bottom, getWidth() - 5, 4);
            painted++;
          }
        }
    }

    private void band(Graphics2D g, MapGeometry geometry, int top, int bottom, int x, int width) {
        int y = (int) geometry.y(top);
        int height = Math.max(1, (int) Math.ceil(geometry.y(bottom) - y));
        g.fillRect(x, y, width, height);
    }

    private void navigate(int line, boolean moveCaret, boolean extendSelection) {
        if (disposed || editor.isDisposed()) return;
        int bounded = Math.clamp(line, 0, editor.getDocument().getLineCount() - 1);
        int offset = editor.getDocument().getLineStartOffset(bounded);
        if (moveCaret && !settings().scrollOnly) {
            int anchor = editor.getSelectionModel().hasSelection()
                    ? editor.getSelectionModel().getLeadSelectionOffset() : editor.getCaretModel().getOffset();
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                for (FoldRegion region : editor.getFoldingModel().getAllFoldRegions()) {
                    if (!region.isExpanded() && offset > region.getStartOffset() && offset < region.getEndOffset()) {
                        region.setExpanded(true);
                    }
                }
            });
            editor.getCaretModel().moveToOffset(offset);
            if (extendSelection) editor.getSelectionModel().setSelection(anchor, offset);
            else editor.getSelectionModel().removeSelection();
            editor.getContentComponent().requestFocusInWindow();
        }
        int y = editor.logicalPositionToXY(new LogicalPosition(bounded, 0)).y;
        if (moveCaret) y -= editor.getScrollingModel().getVisibleArea().height / 2;
        else if (dragging) y += dragPixelOffset;
        editor.getScrollingModel().disableAnimation();
        try { editor.getScrollingModel().scrollVertically(Math.max(0, y)); }
        finally { editor.getScrollingModel().enableAnimation(); }
    }

    @Override public String getToolTipText(MouseEvent event) {
        if (!settings().showPreview || disposed || editor.isDisposed()) return null;
        int line = geometry().line(event.getY());
        var document = editor.getDocument();
        line = Math.min(line, document.getLineCount() - 1);
        StringBuilder preview = new StringBuilder("<html><pre>");
        for (int i = Math.max(0, line - 3); i <= Math.min(document.getLineCount() - 1, line + 3); i++) {
            String text = document.getText(new TextRange(document.getLineStartOffset(i),
                    Math.min(document.getLineEndOffset(i), document.getLineStartOffset(i) + 160)));
            preview.append(i + 1).append(": ").append(text.replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;")).append('\n');
        }
        return preview.append("</pre></html>").toString();
    }

    private boolean popup(MouseEvent event) {
        if (!event.isPopupTrigger()) return false;
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem fit = new JCheckBoxMenuItem("Fit Whole File", settings().fit);
        fit.addActionListener(e -> {
            settings().fit = fit.isSelected();
            CodeScrollManager.getInstance().refreshAll();
        });
        menu.add(fit);
        JCheckBoxMenuItem scrollOnly = new JCheckBoxMenuItem("Preserve Caret When Clicking", settings().scrollOnly);
        scrollOnly.addActionListener(e -> settings().scrollOnly = scrollOnly.isSelected());
        menu.add(scrollOnly);
        JMenuItem preferences = new JMenuItem("Code Scroll Settings...");
        preferences.addActionListener(e -> ShowSettingsUtil.getInstance().showSettingsDialog(
                editor.getProject(), CodeScrollConfigurable.class));
        menu.add(preferences);
        JMenuItem hide = new JMenuItem("Hide Minimaps");
        hide.addActionListener(e -> { settings().enabled = false; CodeScrollManager.getInstance().refreshAll(); });
        menu.add(hide);
        menu.show(this, event.getX(), event.getY());
        return true;
    }

    @Override public void dispose() {
        disposed = true;
        generation++;
        debounce.stop();
        appearance.stop();
        if (renderTask != null) renderTask.cancel(true);
        ToolTipManager.sharedInstance().unregisterComponent(this);
        editor.getComponent().removeComponentListener(editorResize);
        if (oldScrollbarPolicy != -1 && editor instanceof EditorEx ex && !editor.isDisposed()) {
            ex.getScrollPane().setVerticalScrollBarPolicy(oldScrollbarPolicy);
        }
        if (getParent() != null) {
            Container parent = getParent();
            parent.remove(this);
            parent.revalidate();
            parent.repaint();
        }
        frame = null;
    }
}

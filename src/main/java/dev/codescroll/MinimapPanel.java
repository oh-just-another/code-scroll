package dev.codescroll;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.impl.event.MarkupModelListener;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

public final class MinimapPanel extends JPanel implements Disposable {
    /** Width of a hover-only minimap before it is revealed. */
    private static final int COLLAPSED_WIDTH = 8;
    private static final int RESIZE_EDGE = 5;
    private static final int QUICK_HIDE_SIZE = 12;
    /** Bounds syntax-coloring work per frame; later samples fall back to the default foreground. */
    private static final int MAX_TOKENS = 200_000;

    private record Label(int line, String text, boolean declaration) {}
    private record Frame(BufferedImage image, MapGeometry geometry, List<Label> labels, long stamp) {}

    private final Editor editor;
    private final PreviewPopup preview;
    private final Timer debounce;
    private final Timer markupRepaint;
    private final Timer revealTimer;
    private final Timer collapseTimer;
    private final Timer previewTimer;
    private Future<?> renderTask;
    private Frame frame;
    private long generation;
    private boolean disposed;
    private boolean dragging;
    private boolean resizing;
    private boolean pendingClick;
    private boolean mouseInside;
    private boolean revealed;
    private int lastMouseY;
    private double dragOffset;
    private MapGeometry dragGeometry;
    private int dragPixelOffset;
    private int initialX;
    private int initialWidth;
    private int oldScrollbarPolicy = -1;
    private boolean scrollbarHidden;
    private final ComponentAdapter editorResize;

    public MinimapPanel(Editor editor) {
        this.editor = editor;
        preview = new PreviewPopup(editor);
        Disposer.register(this, preview);
        setOpaque(true);
        setFocusable(false);
        updateWidth();
        debounce = timer(settings().debounceMs, this::submitRender);
        markupRepaint = timer(80, this::repaint);
        revealTimer = timer(Math.max(10, settings().hoverDelayMs), () -> setRevealed(true));
        collapseTimer = timer(400, () -> {
            if (!mouseInside && !dragging && !resizing) setRevealed(false);
        });
        previewTimer = timer(Math.max(10, settings().hoverDelayMs), this::showPreview);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) { invalidateMap(); }
        }, this);
        editor.getScrollingModel().addVisibleAreaListener(event -> {
            if (!settings().fit && !dragging) invalidateMap();
            repaint();
        }, this);
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override public void caretPositionChanged(@NotNull CaretEvent event) { repaint(); }
        }, this);
        editor.getSelectionModel().addSelectionListener(new SelectionListener() {
            @Override public void selectionChanged(@NotNull SelectionEvent event) { repaint(); }
        }, this);
        // Diagnostics, VCS ranges, bookmarks and search results arrive without document events.
        listenToMarkup(editor.getMarkupModel());
        listenToMarkup(DocumentMarkupModel.forDocument(editor.getDocument(), editor.getProject(), true));
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(EditorColorsManager.TOPIC, scheme -> invalidateMap());
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
                preview.hide();
            }
        });
        editorResize = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateWidth(); }
        };
        editor.getComponent().addComponentListener(editorResize);
        var mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { pressed(event); }
            @Override public void mouseReleased(MouseEvent event) { released(event); }
            @Override public void mouseDragged(MouseEvent event) { dragged(event); }
            @Override public void mouseMoved(MouseEvent event) { moved(event); }
            @Override public void mouseEntered(MouseEvent event) { entered(); }
            @Override public void mouseExited(MouseEvent event) { exited(); }
            @Override public void mouseWheelMoved(MouseWheelEvent event) { wheel(event); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        if (settings().hideScrollbar && editor instanceof EditorEx ex) {
            oldScrollbarPolicy = ex.getScrollPane().getVerticalScrollBarPolicy();
        }
        invalidateMap();
    }

    private static Timer timer(int delay, Runnable action) {
        Timer timer = new Timer(delay, event -> action.run());
        timer.setRepeats(false);
        return timer;
    }

    private void listenToMarkup(MarkupModel model) {
        if (!(model instanceof MarkupModelEx extended)) return;
        extended.addMarkupModelListener(this, new MarkupModelListener() {
            @Override public void afterAdded(@NotNull RangeHighlighterEx highlighter) { scheduleMarkupRepaint(); }
            @Override public void afterRemoved(@NotNull RangeHighlighterEx highlighter) { scheduleMarkupRepaint(); }
            @Override public void attributesChanged(@NotNull RangeHighlighterEx highlighter,
                                                    boolean renderersChanged, boolean fontStyleOrColorChanged) {
                scheduleMarkupRepaint();
            }
        });
    }

    private void scheduleMarkupRepaint() {
        // Highlighting passes add hundreds of highlighters at once; coalesce them into one repaint.
        if (SwingUtilities.isEventDispatchThread()) {
            if (!disposed && !markupRepaint.isRunning()) markupRepaint.restart();
        } else {
            SwingUtilities.invokeLater(this::scheduleMarkupRepaint);
        }
    }

    private CodeScrollSettings.State settings() { return CodeScrollSettings.getInstance().getState(); }

    private boolean collapsed() {
        return settings().hoverOnly && !revealed && !dragging && !resizing;
    }

    void updateWidth() {
        int width = targetWidth();
        if (getPreferredSize().width != width) {
            setPreferredSize(new Dimension(width, 0));
            revalidate();
        }
    }

    private int targetWidth() {
        var s = settings();
        if (collapsed()) return COLLAPSED_WIDTH;
        int width = s.widthFor(editor.getEditorKind());
        if (s.autoWidth && !resizing && editor.getComponent().getWidth() > 0) {
            width = Math.min(width, Math.max(40, editor.getComponent().getWidth() / 5));
        }
        return width;
    }

    private void setRevealed(boolean value) {
        if (disposed || revealed == value) return;
        revealed = value;
        if (!value) preview.hide();
        updateWidth();
        invalidateMap();
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
                settings().fill, settings().pixelsPerLine, visibleStart(), visibleEnd());
    }

    private boolean inLineRange() {
        int lines = editor.getDocument().getLineCount();
        return lines >= settings().minLines && lines <= settings().maxLines;
    }

    /** Console/untyped editors can keep a navigable but text-free map, as configured. */
    private boolean textless() {
        EditorKind kind = editor.getEditorKind();
        return (kind == EditorKind.CONSOLE && settings().emptyConsole)
                || (kind == EditorKind.UNTYPED && settings().emptyUntyped);
    }

    private void invalidateMap() {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::invalidateMap);
            return;
        }
        if (disposed || editor.isDisposed()) return;
        boolean inRange = inLineRange();
        boolean shown = inRange || settings().outOfRangeEmpty;
        if (!inRange) frame = null;
        if (oldScrollbarPolicy != -1 && editor instanceof EditorEx ex && scrollbarHidden != shown) {
            ex.getScrollPane().setVerticalScrollBarPolicy(shown
                    ? ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER : oldScrollbarPolicy);
            scrollbarHidden = shown;
        }
        if (isVisible() != shown) {
            setVisible(shown);
            editor.getComponent().revalidate();
        }
        generation++;
        if (renderTask != null) renderTask.cancel(true);
        if (inRange) {
            debounce.setInitialDelay(settings().debounceMs);
            debounce.restart();
        }
        repaint();
    }

    private void submitRender() {
        if (disposed || editor.isDisposed() || getWidth() < 40 || getHeight() <= 0 || !isShowing()) return;
        if (dragging && !settings().fit) return;
        var s = settings();
        int lines = editor.getDocument().getLineCount();
        if (!inLineRange()) {
            frame = null;
            repaint();
            return;
        }
        MapGeometry geometry = MapGeometry.create(lines, getHeight(), s.fit, s.fill, s.pixelsPerLine,
                visibleStart(), visibleEnd());
        int width = Math.min(RasterRenderer.MAX_WIDTH, getWidth());
        EditorColorsScheme scheme = editor.getColorsScheme();
        int foreground = scheme.getDefaultForeground().getRGB();
        int background = scheme.getDefaultBackground().getRGB();
        boolean textless = textless();
        boolean accurate = s.renderStyle == CodeScrollSettings.RenderStyle.ACCURATE;
        // The editor's incremental highlighter already holds lexer state for the whole document.
        EditorHighlighter highlighter = s.syntaxColors && editor instanceof EditorEx ex ? ex.getHighlighter() : null;
        boolean labels = !textless && (s.showMarkers || s.showMethods);
        String words = s.showMarkers ? s.markerWords : "";
        String regex = s.showMarkers ? s.markerRegex : "";
        boolean methods = s.showMethods;
        String methodNames = s.methodNames;
        String methodAnnotations = s.methodAnnotations;
        boolean markdown = isMarkdown(FileDocumentManager.getInstance().getFile(editor.getDocument()));
        long request = generation;
        renderTask = ReadAction.nonBlocking(() -> {
            var document = editor.getDocument();
            CharSequence text = document.getImmutableCharSequence();
            long stamp = document.getModificationStamp();
            int capacity = textless ? 0 : geometry.height() * 3;
            int[] starts = new int[capacity];
            int[] ends = new int[capacity];
            int[] rows = new int[capacity];
            int samples = 0;
            for (int y = 0; y < geometry.height() && !textless; y++) {
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
            List<RasterRenderer.Token> tokens = highlighter == null ? List.of()
                    : tokens(highlighter, text.length(), starts, ends, samples);
            BufferedImage image = RasterRenderer.render(text, width, geometry.height(),
                    Arrays.copyOf(starts, samples), Arrays.copyOf(ends, samples), Arrays.copyOf(rows, samples),
                    foreground, background, tokens, accurate, ProgressManager::checkCanceled);
            List<Label> found = new ArrayList<>();
            if (labels) {
                for (var marker : MarkerScanner.scan(text, words, methods, methodNames, methodAnnotations,
                        regex, markdown, 2000, ProgressManager::checkCanceled)) {
                    found.add(new Label(document.getLineNumber(marker.offset()), marker.label(), marker.declaration()));
                }
            }
            return new Frame(image, geometry, List.copyOf(found), stamp);
        }).expireWith(this).coalesceBy(this)
                .finishOnUiThread(ModalityState.any(), result -> {
                    if (disposed || editor.isDisposed() || request != generation
                            || result.stamp() != editor.getDocument().getModificationStamp()) return;
                    frame = result;
                    repaint();
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    static boolean isMarkdown(VirtualFile file) {
        if (file == null) return false;
        String extension = file.getExtension();
        if (extension != null && List.of("md", "markdown", "mdx", "mkd").contains(extension.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return "markdown".equalsIgnoreCase(file.getFileType().getName());
    }

    /** Colors only the sampled ranges; tokens stay sorted by start offset as the renderer requires. */
    private static List<RasterRenderer.Token> tokens(EditorHighlighter highlighter, int length,
                                                     int[] starts, int[] ends, int samples) {
        List<RasterRenderer.Token> tokens = new ArrayList<>();
        int previousStart = -1;
        for (int i = 0; i < samples && tokens.size() < MAX_TOKENS; i++) {
            if ((i & 63) == 0) ProgressManager.checkCanceled();
            if (ends[i] <= starts[i]) continue;
            HighlighterIterator iterator = highlighter.createIterator(starts[i]);
            while (!iterator.atEnd() && iterator.getStart() < ends[i] && tokens.size() < MAX_TOKENS) {
                int start = Math.clamp(iterator.getStart(), 0, length);
                int end = Math.clamp(iterator.getEnd(), start, length);
                TextAttributes attributes = iterator.getTextAttributes();
                Color color = attributes == null ? null : attributes.getForegroundColor();
                if (color != null && start >= previousStart) {
                    tokens.add(new RasterRenderer.Token(start, end, color.getRGB()));
                    previousStart = start;
                }
                iterator.advance();
            }
        }
        return tokens;
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (disposed || editor.isDisposed()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            var s = settings();
            EditorColorsScheme scheme = editor.getColorsScheme();
            Color background = scheme.getDefaultBackground();
            g.setColor(background);
            g.fillRect(0, 0, getWidth(), getHeight());
            Color accent = scheme.getColor(EditorColors.CARET_COLOR);
            if (accent == null) accent = scheme.getDefaultForeground();
            MapGeometry geometry = geometry();
            Rectangle viewport = viewport(geometry);
            if (collapsed()) {
                g.setColor(CodeScrollSettings.color(s.viewportBorderColor, s.viewportBorderOpacity));
                g.fillRect(getWidth() / 2 - 1, viewport.y, 3, viewport.height);
                return;
            }
            if (frame != null) g.drawImage(frame.image(), 0, 0, null);
            var selection = editor.getSelectionModel();
            if (selection.hasSelection()) {
                int top = editor.getDocument().getLineNumber(selection.getSelectionStart());
                int bottom = editor.getDocument().getLineNumber(selection.getSelectionEnd()) + 1;
                Color color = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR);
                g.setColor(alpha(color == null ? new Color(70, 135, 230) : color, 110));
                band(g, geometry, top, bottom, 0, getWidth());
            }
            paintMarkup(g, geometry, editor.getMarkupModel(), accent);
            if (editor instanceof EditorEx ex) paintMarkup(g, geometry, ex.getFilteredDocumentMarkupModel(), accent);
            g.setColor(CodeScrollSettings.color(s.viewportColor, s.viewportOpacity));
            g.fill(viewport);
            if (s.viewportBorderWidth > 0) {
                g.setColor(CodeScrollSettings.color(s.viewportBorderColor, s.viewportBorderOpacity));
                int border = Math.min(s.viewportBorderWidth, Math.max(1, viewport.height / 2));
                g.fillRect(viewport.x, viewport.y, viewport.width, border);
                g.fillRect(viewport.x, viewport.y + viewport.height - border, viewport.width, border);
                g.fillRect(viewport.x, viewport.y, border, viewport.height);
                g.fillRect(viewport.x + viewport.width - border, viewport.y, border, viewport.height);
            }
            g.setColor(alpha(accent, 170));
            int caretLine = editor.getCaretModel().getLogicalPosition().line;
            band(g, geometry, caretLine, caretLine + 1, 0, getWidth());
            if (frame != null) paintLabels(g, geometry, background, accent, scheme.getDefaultForeground());
            if (mouseInside && s.showQuickHide) paintQuickHide(g, background, scheme.getDefaultForeground());
            g.setColor(alpha(accent, 55));
            int edge = s.leftAligned ? getWidth() - 1 : 0;
            g.drawLine(edge, 0, edge, getHeight());
        } finally { g.dispose(); }
    }

    private void paintLabels(Graphics2D g, MapGeometry geometry, Color background, Color accent, Color foreground) {
        int size = settings().markerFontSize;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font marker = new Font(Font.MONOSPACED, Font.PLAIN, size);
        Font declaration = marker.deriveFont(Font.BOLD);
        int height = size + 2;
        int previousBottom = -1;
        for (Label label : frame.labels()) {
            int y = (int) geometry.y(label.line());
            // Closely spaced labels are skipped rather than drawn on top of each other.
            if (y < 0 || y >= getHeight() || y < previousBottom) continue;
            g.setColor(alpha(background, 225));
            g.fillRect(3, y, getWidth() - 9, height);
            g.setFont(label.declaration() ? declaration : marker);
            g.setColor(label.declaration() ? foreground : accent);
            g.drawString(label.text(), 4, y + size);
            previousBottom = y + height + 1;
        }
    }

    private Rectangle quickHideBounds() {
        int x = settings().leftAligned ? 2 : getWidth() - QUICK_HIDE_SIZE - 2;
        return new Rectangle(x, 2, QUICK_HIDE_SIZE, QUICK_HIDE_SIZE);
    }

    private void paintQuickHide(Graphics2D g, Color background, Color foreground) {
        Rectangle box = quickHideBounds();
        g.setColor(alpha(background, 235));
        g.fill(box);
        g.setColor(alpha(foreground, 200));
        g.drawRect(box.x, box.y, box.width - 1, box.height - 1);
        g.drawLine(box.x + 3, box.y + 3, box.x + box.width - 4, box.y + box.height - 4);
        g.drawLine(box.x + box.width - 4, box.y + 3, box.x + 3, box.y + box.height - 4);
    }

    private Rectangle viewport(MapGeometry geometry) {
        int top = (int) Math.max(0, geometry.y(visibleStart()));
        int bottom = (int) Math.min(getHeight(), Math.ceil(geometry.y(visibleEnd())));
        return new Rectangle(0, top, getWidth(), Math.max(4, bottom - top));
    }

    private void paintMarkup(Graphics2D g, MapGeometry geometry, MarkupModel model, Color accent) {
        if (!(model instanceof MarkupModelEx extended)) return;
        var s = settings();
        var document = editor.getDocument();
        if (document.getLineCount() == 0) return;
        int start = document.getLineStartOffset(Math.min(document.getLineCount() - 1, geometry.line(0)));
        int end = document.getLineEndOffset(Math.min(document.getLineCount() - 1, geometry.line(getHeight())));
        EditorColorsScheme scheme = editor.getColorsScheme();
        int width = getWidth();
        // VCS ranges sit on the editor-facing edge, like the gutter; diagnostics on the far edge.
        int nearEdge = s.leftAligned ? width - 3 : 0;
        int farEdge = s.leftAligned ? 0 : width - 5;
        long deadline = System.nanoTime() + 2_000_000;
        int painted = 0;
        try (var iterator = extended.overlappingIterator(start, end)) {
            while (iterator.hasNext() && painted < 2000 && System.nanoTime() < deadline) {
                RangeHighlighter highlighter = iterator.next();
                if (!highlighter.isValid()) continue;
                Color stripe = highlighter.getErrorStripeMarkColor(scheme);
                TextAttributesKey key = highlighter.getTextAttributesKey();
                var kind = MarkupClassifier.classify(key == null ? null : key.getExternalName(),
                        highlighter instanceof RangeHighlighterEx ex && ex.isThinErrorStripeMark(),
                        className(highlighter.getLineMarkerRenderer()),
                        className(highlighter.getGutterIconRenderer()), stripe != null);
                TextAttributes attributes = null;
                if (kind == MarkupClassifier.Kind.SEARCH || kind == MarkupClassifier.Kind.OTHER) {
                    attributes = highlighter.getTextAttributes(scheme);
                }
                Color background = attributes == null ? null : attributes.getBackgroundColor();
                int top = document.getLineNumber(Math.min(document.getTextLength(), highlighter.getStartOffset()));
                int bottom = document.getLineNumber(Math.min(document.getTextLength(), highlighter.getEndOffset())) + 1;
                switch (kind) {
                    case SEARCH -> {
                        if (!s.showSearch) continue;
                        Color color = background != null ? background : stripe;
                        if (color == null) continue;
                        g.setColor(alpha(color, 150));
                        band(g, geometry, top, bottom, 0, width);
                    }
                    case BOOKMARK -> {
                        if (!s.showBookmarks) continue;
                        g.setColor(stripe != null ? stripe : accent);
                        int y = (int) geometry.y(top);
                        g.fillRect(0, y, width, 2);
                        int tip = s.leftAligned ? width - 1 : 0;
                        int direction = s.leftAligned ? -1 : 1;
                        g.fillPolygon(new int[]{tip, tip + 6 * direction, tip}, new int[]{y - 4, y + 1, y + 6}, 3);
                    }
                    case VCS -> {
                        if (!s.showVcs || stripe == null) continue;
                        g.setColor(stripe);
                        band(g, geometry, top, bottom, nearEdge, 3);
                    }
                    case DIAGNOSTIC -> {
                        if (!s.showDiagnostics) continue;
                        g.setColor(s.fullLineDiagnostics ? alpha(stripe, 110) : stripe);
                        if (s.fullLineDiagnostics) band(g, geometry, top, bottom, 0, width);
                        else band(g, geometry, top, bottom, farEdge, 4);
                    }
                    case OTHER -> {
                        Color color = background != null ? background : stripe;
                        if (color == null) continue;
                        g.setColor(s.fullLineMarkup ? alpha(color, 90) : color);
                        if (s.fullLineMarkup) band(g, geometry, top, bottom, 0, width);
                        else band(g, geometry, top, bottom, farEdge, 4);
                    }
                }
                painted++;
            }
        }
    }

    private static String className(Object value) {
        return value == null ? null : value.getClass().getName();
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private void band(Graphics2D g, MapGeometry geometry, int top, int bottom, int x, int width) {
        int y = (int) geometry.y(top);
        int height = Math.max(1, (int) Math.ceil(geometry.y(bottom) - y));
        g.fillRect(x, y, width, height);
    }

    private boolean onResizeEdge(int x) {
        return !settings().lockWidth && (settings().leftAligned ? x > getWidth() - RESIZE_EDGE : x < RESIZE_EDGE);
    }

    private void pressed(MouseEvent event) {
        previewTimer.stop();
        preview.hide();
        if (popup(event) || !SwingUtilities.isLeftMouseButton(event)) return;
        if (collapsed()) {
            setRevealed(true);
            return;
        }
        if (settings().showQuickHide && quickHideBounds().contains(event.getPoint())) {
            // Disposes this panel; nothing below may touch it.
            CodeScrollManager.getInstance().hide(editor);
            return;
        }
        initialX = event.getXOnScreen();
        initialWidth = settings().widthFor(editor.getEditorKind());
        resizing = onResizeEdge(event.getX());
        if (resizing) return;
        MapGeometry geometry = geometry();
        dragging = viewport(geometry).contains(event.getPoint()) && !event.isShiftDown();
        dragOffset = event.getY() - geometry.y(visibleStart());
        dragGeometry = geometry;
        dragPixelOffset = editor.getScrollingModel().getVisibleArea().y
                - editor.logicalPositionToXY(new LogicalPosition(visibleStart(), 0)).y;
        if (dragging) return;
        switch (settings().clickTiming) {
            case DOWN -> click(event);
            case UP -> pendingClick = true;
            case NONE -> { }
        }
    }

    private void released(MouseEvent event) {
        if (resizing) CodeScrollManager.getInstance().relayoutAll();
        if (pendingClick && contains(event.getPoint())) click(event);
        pendingClick = resizing = dragging = false;
        dragGeometry = null;
        if (!settings().fit) invalidateMap();
        if (!mouseInside && settings().hoverOnly) collapseTimer.restart();
        popup(event);
    }

    private void dragged(MouseEvent event) {
        if (resizing) {
            int delta = event.getXOnScreen() - initialX;
            settings().setWidthFor(editor.getEditorKind(), initialWidth + (settings().leftAligned ? delta : -delta));
            updateWidth();
            invalidateMap();
        } else if (dragging) {
            navigate(dragGeometry.line(event.getY() - dragOffset), 0, false, false);
        }
    }

    private void moved(MouseEvent event) {
        lastMouseY = event.getY();
        setCursor(Cursor.getPredefinedCursor(onResizeEdge(event.getX()) ? Cursor.E_RESIZE_CURSOR : Cursor.HAND_CURSOR));
        if (preview.isShowing()) showPreview();
        else if (settings().showPreview && !collapsed()) {
            previewTimer.setInitialDelay(Math.max(10, settings().hoverDelayMs));
            previewTimer.restart();
        }
    }

    private void entered() {
        mouseInside = true;
        collapseTimer.stop();
        if (settings().hoverOnly && !revealed) {
            revealTimer.setInitialDelay(Math.max(10, settings().hoverDelayMs));
            revealTimer.restart();
        }
        repaint();
    }

    private void exited() {
        mouseInside = false;
        revealTimer.stop();
        previewTimer.stop();
        preview.hide();
        if (settings().hoverOnly && !dragging && !resizing) collapseTimer.restart();
        repaint();
    }

    private void wheel(MouseWheelEvent event) {
        double rotation = event.getPreciseWheelRotation();
        if (settings().previewWheel && preview.isShowing()) {
            preview.scrollTo(preview.line() + (int) Math.signum(rotation) * Math.max(1, (int) Math.abs(rotation * 3)));
        } else {
            editor.getScrollingModel().scrollVertically(Math.max(0,
                    editor.getScrollingModel().getVerticalScrollOffset()
                            + (int) (rotation * editor.getLineHeight() * 3)));
        }
        event.consume();
    }

    private void showPreview() {
        if (disposed || editor.isDisposed() || !settings().showPreview || dragging || resizing
                || !mouseInside || collapsed()) return;
        preview.show(this, settings().leftAligned, lastMouseY, geometry().line(lastMouseY));
    }

    private void click(MouseEvent event) {
        // With "click by mouse position", the column under the pointer is used as well as the line.
        int column = settings().clickByMousePosition ? Math.max(0, event.getX() - 4) : 0;
        navigate(geometry().line(event.getY()), column, true, event.isShiftDown());
    }

    private void navigate(int line, int column, boolean moveCaret, boolean extendSelection) {
        if (disposed || editor.isDisposed()) return;
        var document = editor.getDocument();
        int bounded = Math.clamp(line, 0, Math.max(0, document.getLineCount() - 1));
        int offset = Math.min(document.getLineEndOffset(bounded),
                editor.logicalPositionToOffset(new LogicalPosition(bounded, column)));
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

    private boolean popup(MouseEvent event) {
        if (!event.isPopupTrigger()) return false;
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem fit = new JCheckBoxMenuItem("Fit Whole File", settings().fit);
        fit.addActionListener(e -> {
            settings().fit = fit.isSelected();
            CodeScrollManager.getInstance().refreshAll();
        });
        menu.add(fit);
        JCheckBoxMenuItem fill = new JCheckBoxMenuItem("Fill Editor Height", settings().fill);
        fill.addActionListener(e -> {
            settings().fill = fill.isSelected();
            CodeScrollManager.getInstance().refreshAll();
        });
        menu.add(fill);
        JCheckBoxMenuItem scrollOnly = new JCheckBoxMenuItem("Preserve Caret When Clicking", settings().scrollOnly);
        scrollOnly.addActionListener(e -> settings().scrollOnly = scrollOnly.isSelected());
        menu.add(scrollOnly);
        menu.addSeparator();
        JMenuItem preferences = new JMenuItem("Code Scroll Settings...");
        preferences.addActionListener(e -> ShowSettingsUtil.getInstance().showSettingsDialog(
                editor.getProject(), CodeScrollConfigurable.class));
        menu.add(preferences);
        JMenuItem hideHere = new JMenuItem("Hide in This Editor");
        hideHere.addActionListener(e -> CodeScrollManager.getInstance().hide(editor));
        menu.add(hideHere);
        JMenuItem hide = new JMenuItem("Hide All Minimaps");
        hide.addActionListener(e -> { settings().enabled = false; CodeScrollManager.getInstance().refreshAll(); });
        menu.add(hide);
        menu.show(this, event.getX(), event.getY());
        return true;
    }

    @Override public void dispose() {
        disposed = true;
        generation++;
        for (Timer timer : new Timer[]{debounce, markupRepaint, revealTimer, collapseTimer, previewTimer}) timer.stop();
        if (renderTask != null) renderTask.cancel(true);
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

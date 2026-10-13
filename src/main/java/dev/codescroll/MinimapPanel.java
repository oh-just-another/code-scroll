package dev.codescroll;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
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
    /** Lines sampled per raster row when several share it; their coverage is averaged (antialiasing). */
    private static final int MAX_LINES_PER_ROW = 8;

    private record Label(int line, String text, boolean declaration) {}
    /** {@code geometry} is in panel (logical) pixels; the image has {@code rasterScale} device pixels per one. */
    private record Frame(BufferedImage image, MapGeometry geometry, double rasterScale, List<Label> labels, long stamp,
                         Overview overview) {}
    /** Whole-document raster; {@code rowsPerLine} is in its own device rows. */
    private record Overview(BufferedImage image, double rowsPerLine, double rasterScale, int width, long stamp) {}

    private final Editor editor;
    private final PreviewPopup preview;
    private final Timer debounce;
    private final Timer markupRepaint;
    private final Timer revealTimer;
    private final Timer collapseTimer;
    private final Timer previewTimer;
    private final Timer scrollRender;
    private Future<?> renderTask;
    /** A scroll needed a new raster while one was still being rendered; render again when it lands. */
    private boolean renderAgain;
    /** Labels depend only on text and label settings, not on the scroll position: reuse them. */
    private List<Label> cachedLabels;
    private long cachedLabelStamp = -1;
    private String cachedLabelKey;
    private Frame frame;
    private long generation;
    private boolean disposed;
    private boolean dragging;
    private boolean resizing;
    private boolean pendingClick;
    private boolean mouseInside;
    private boolean revealed;
    private int lastMouseY;
    private boolean hoverFrame;
    private int dragStartY;
    private double dragStartFrameTop;
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
        scrollRender = timer(30, this::submitRender);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) { invalidateMap(); }
        }, this);
        editor.getScrollingModel().addVisibleAreaListener(event -> {
            // The raster covers more than the panel; render the next window before the shown part leaves it.
            if (frame != null && !covers(frame.geometry(), geometry(), getHeight() / 2.0)) requestScrollRender();
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
        // Tab switches change showing state without component events: refresh a stale map when shown again,
        // and drop the raster of hidden tabs so background editors do not each hold an image.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || disposed) return;
            if (isShowing()) {
                if (inDialog()) {
                    CodeScrollManager.getInstance().hide(editor);
                    return;
                }
                if (frame == null || frame.stamp() != editor.getDocument().getModificationStamp()) invalidateMap();
            } else {
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

    /** Small editors in modal dialogs (settings previews, code style samples) get no minimap; diffs do. */
    private boolean inDialog() {
        return editor.getEditorKind() != EditorKind.DIFF
                && SwingUtilities.getWindowAncestor(editor.getComponent()) instanceof JDialog;
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

    /** First visible logical line, fractional when the editor is scrolled to the middle of a line. */
    private double topLine() {
        Rectangle area = editor.getScrollingModel().getVisibleArea();
        int line = editor.xyToLogicalPosition(new Point(0, area.y)).line;
        double lineHeight = Math.max(1, editor.getLineHeight());
        return line + (area.y - editor.logicalPositionToXY(new LogicalPosition(line, 0)).y) / lineHeight;
    }

    /**
     * How many logical lines the visible area spans. The map draws logical lines, so folded regions,
     * inlays and soft wraps must be measured in them too, not as visible height / line height.
     */
    private double spanLines() {
        Rectangle area = editor.getScrollingModel().getVisibleArea();
        int y = area.y + area.height;
        int line = editor.xyToLogicalPosition(new Point(0, y)).line;
        double lineHeight = Math.max(1, editor.getLineHeight());
        double fraction = Math.clamp((y - editor.logicalPositionToXY(new LogicalPosition(line, 0)).y) / lineHeight, 0, 1);
        return Math.max(1, line + fraction - topLine());
    }

    /** Live geometry for the current scroll position; cheap, computed on every paint and event. */
    private MapGeometry geometry() {
        var s = settings();
        return MapGeometry.forScroll(editor.getDocument().getLineCount(), getHeight(), s.fit, s.fill,
                s.pixelsPerLine, topLine(), spanLines());
    }

    /** Device pixels per panel pixel: 2 on Retina, 1 on standard screens. */
    private double rasterScale() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        double scale = configuration == null ? 1 : configuration.getDefaultTransform().getScaleY();
        return Math.clamp(scale, 1, 3);
    }

    /**
     * Whether a rendered raster holds every map row the panel shows at {@code live}, plus {@code margin}
     * rows beyond each edge (the margin is waived at the document's own start and end).
     */
    private boolean covers(MapGeometry raster, MapGeometry live, double margin) {
        if (raster.scale() != live.scale() || raster.lines() != live.lines()) return false;
        double top = Math.max(0, live.origin() - margin);
        double bottom = Math.min(live.origin() + getHeight() + margin, live.documentHeight());
        return top >= raster.origin() - 0.5 && bottom <= raster.origin() + raster.height() + 0.5;
    }

    /**
     * Scrolling must not cancel a render in flight: with continuous scrolling each request would cancel
     * the previous one and no raster would ever finish. Queue one more render instead.
     */
    private void requestScrollRender() {
        if (renderTask != null && !renderTask.isDone()) renderAgain = true;
        else if (!scrollRender.isRunning()) scrollRender.restart();
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
        var s = settings();
        int lines = editor.getDocument().getLineCount();
        if (!inLineRange()) {
            frame = null;
            repaint();
            return;
        }
        // Render a window up to the raster limit around the shown part, so scrolling just moves the image.
        // The raster uses device pixels, so HiDPI screens get a sharp map instead of an upscaled one.
        double rasterScale = rasterScale();
        MapGeometry live = geometry();
        double documentHeight = live.documentHeight();
        int maxWindow = (int) (RasterRenderer.MAX_HEIGHT / rasterScale);
        int windowHeight = (int) Math.min(maxWindow, Math.max(1, Math.ceil(documentHeight)));
        double windowOrigin = documentHeight <= maxWindow ? 0 : Math.clamp(
                live.origin() - (windowHeight - getHeight()) / 2.0, 0, documentHeight - windowHeight);
        MapGeometry geometry = live.window(windowHeight, Math.floor(windowOrigin));
        MapGeometry device = new MapGeometry(geometry.lines(), (int) Math.ceil(windowHeight * rasterScale),
                geometry.scale() * rasterScale, geometry.origin() * rasterScale);
        int charWidth = (int) Math.max(1, Math.round(rasterScale));
        int width = Math.min(RasterRenderer.MAX_WIDTH, (int) Math.round(getWidth() * rasterScale));
        EditorColorsScheme scheme = editor.getColorsScheme();
        int foreground = scheme.getDefaultForeground().getRGB();
        int background = scheme.getDefaultBackground().getRGB();
        boolean textless = textless();
        boolean accurate = s.renderStyle == CodeScrollSettings.RenderStyle.ACCURATE;
        int intensity = s.textIntensity;
        // The editor's incremental highlighter already holds lexer state for the whole document.
        EditorHighlighter highlighter = s.syntaxColors && editor instanceof EditorEx ex ? ex.getHighlighter() : null;
        var project = editor.getProject();
        VirtualFile colorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        boolean labels = !textless && (s.showMarkers || s.showMethods);
        String labelKey = String.join("\u0000", String.valueOf(labels), s.showMarkers ? s.markerWords : "",
                s.showMarkers ? s.markerRegex : "", String.valueOf(s.showMethods), s.methodNames,
                s.methodAnnotations, String.valueOf(isMarkdown(FileDocumentManager.getInstance().getFile(editor.getDocument()))));
        List<Label> reusableLabels = labelKey.equals(cachedLabelKey) ? cachedLabels : null;
        Overview reusableOverview = frame == null ? null : frame.overview();
        long reusableStamp = cachedLabelStamp;
        String words = s.showMarkers ? s.markerWords : "";
        String regex = s.showMarkers ? s.markerRegex : "";
        boolean methods = s.showMethods;
        String methodNames = s.methodNames;
        String methodAnnotations = s.methodAnnotations;
        VirtualFile labelFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        boolean markdown = isMarkdown(labelFile);
        boolean hashComments = hashComments(labelFile);
        long request = generation;
        renderTask = ReadAction.nonBlocking(() -> {
            var document = editor.getDocument();
            CharSequence text = document.getImmutableCharSequence();
            long stamp = document.getModificationStamp();
            TokenColors palette = highlighter == null ? null : new TokenColors(scheme, project, colorFile);
            BufferedImage image = rasterize(document, text, device, width, charWidth, highlighter, palette,
                    foreground, background, accurate, intensity, textless);
            // Long documents also get a coarse raster of the whole file, drawn wherever the precise window
            // does not reach yet (fast drags), so the map never shows blank stretches while catching up.
            Overview overview = null;
            if (!textless && documentHeight > maxWindow) {
                if (reusableOverview != null && reusableOverview.stamp() == stamp && reusableOverview.width() == width
                        && reusableOverview.rasterScale() == rasterScale) {
                    overview = reusableOverview;
                } else {
                    int rows = RasterRenderer.MAX_HEIGHT;
                    MapGeometry whole = new MapGeometry(geometry.lines(), rows, (double) rows / geometry.lines(), 0);
                    overview = new Overview(rasterize(document, text, whole, width, charWidth, highlighter, palette,
                            foreground, background, accurate, intensity, false), whole.scale(), rasterScale, width, stamp);
                }
            }
            List<Label> found = new ArrayList<>();
            if (reusableLabels != null && reusableStamp == stamp) {
                found = reusableLabels;
            } else if (labels) {
                for (var marker : MarkerScanner.scan(text, words, methods, methodNames, methodAnnotations,
                        regex, markdown, hashComments, 2000, ProgressManager::checkCanceled)) {
                    found.add(new Label(document.getLineNumber(marker.offset()), marker.label(), marker.declaration()));
                }
            }
            return new Frame(image, geometry, rasterScale, List.copyOf(found), stamp, overview);
        }).expireWith(this).coalesceBy(this)
                .finishOnUiThread(ModalityState.any(), result -> {
                    if (disposed || editor.isDisposed() || request != generation
                            || result.stamp() != editor.getDocument().getModificationStamp()) return;
                    frame = result;
                    cachedLabels = result.labels();
                    cachedLabelStamp = result.stamp();
                    cachedLabelKey = labelKey;
                    repaint();
                    if (renderAgain) {
                        renderAgain = false;
                        if (!covers(result.geometry(), geometry(), getHeight() / 2.0)) scrollRender.restart();
                    }
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    /** Languages where {@code #} never starts a comment; elsewhere (Python, shell, YAML, unknown) it may. */
    private static final java.util.Set<String> NO_HASH_COMMENTS = java.util.Set.of("java", "kt", "kts", "scala",
            "groovy", "c", "h", "cc", "cpp", "hpp", "cs", "m", "mm", "swift", "go", "rs", "dart", "js", "jsx",
            "mjs", "cjs", "ts", "tsx", "vue", "svelte", "css", "scss", "sass", "less", "json", "jsonc", "html",
            "htm", "xml", "sql", "proto");

    static boolean hashComments(VirtualFile file) {
        String extension = file == null ? null : file.getExtension();
        return extension == null || !NO_HASH_COMMENTS.contains(extension.toLowerCase(Locale.ROOT));
    }

    /** Samples the rows of {@code device} (device-pixel geometry) and renders them antialiased. */
    private static BufferedImage rasterize(com.intellij.openapi.editor.Document document, CharSequence text,
                                           MapGeometry device, int width, int charWidth,
                                           EditorHighlighter highlighter, TokenColors palette,
                                           int foreground, int background, boolean accurate, int intensity,
                                           boolean textless) {
        int capacity = textless ? 0 : device.height() * MAX_LINES_PER_ROW;
        int[] starts = new int[capacity];
        int[] ends = new int[capacity];
        int[] rows = new int[capacity];
        float[] phases = new float[capacity];
        float[] coverages = new float[capacity];
        int samples = 0;
        double lineScale = device.scale();
        int lineCount = document.getLineCount();
        for (int y = 0; y < device.height() && !textless; y++) {
            if ((y & 63) == 0) ProgressManager.checkCanceled();
            double rowTop = y + device.origin();
            if (rowTop >= device.documentHeight()) break;
            // Every line overlapping this row contributes by the share of the row it covers.
            int first = (int) Math.floor(rowTop / lineScale);
            int last = Math.min(lineCount - 1, (int) Math.floor((rowTop + 1 - 1e-9) / lineScale));
            int count = last - first + 1;
            if (count <= 0) continue;
            int taken = Math.min(count, MAX_LINES_PER_ROW);
            // When more lines share a row than are sampled, the sampled ones stand in for the rest.
            double stand = (double) count / taken;
            for (int k = 0; k < taken; k++) {
                int line = taken == count ? first + k : first + (int) ((long) k * (count - 1) / Math.max(1, taken - 1));
                double lineTop = line * lineScale;
                double overlapTop = Math.max(rowTop, lineTop);
                double overlapBottom = Math.min(rowTop + 1, lineTop + lineScale);
                if (overlapBottom <= overlapTop) continue;
                // A write action cancels this read before stale line offsets are published.
                starts[samples] = document.getLineStartOffset(line);
                ends[samples] = document.getLineEndOffset(line);
                phases[samples] = (float) Math.clamp(((overlapTop + overlapBottom) / 2 - lineTop) / lineScale, 0, 0.999);
                coverages[samples] = (float) Math.min(1, (overlapBottom - overlapTop) * stand);
                rows[samples++] = y;
            }
        }
        List<RasterRenderer.Token> tokens = highlighter == null ? List.of()
                : tokens(highlighter, palette, text.length(), starts, ends, samples);
        return RasterRenderer.render(text, width, device.height(),
                Arrays.copyOf(starts, samples), Arrays.copyOf(ends, samples), Arrays.copyOf(rows, samples),
                Arrays.copyOf(phases, samples), Arrays.copyOf(coverages, samples), device.scale(), charWidth,
                RasterRenderer.INDENT * charWidth, foreground, background, tokens, accurate, intensity,
                ProgressManager::checkCanceled);
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
    /**
     * Resolves token colors with this render's own syntax highlighters (one per token language, so injected
     * and layered languages keep their colors) instead of the editor highlighter's attribute cache, which is
     * a plain map the EDT fills concurrently.
     */
    private static final class TokenColors {
        private final EditorColorsScheme scheme;
        private final com.intellij.openapi.project.Project project;
        private final VirtualFile file;
        private final java.util.Map<com.intellij.lang.Language, SyntaxHighlighter> highlighters = new java.util.HashMap<>();
        private final java.util.Map<com.intellij.psi.tree.IElementType, Integer> colors = new java.util.HashMap<>();

        TokenColors(EditorColorsScheme scheme, com.intellij.openapi.project.Project project, VirtualFile file) {
            this.scheme = scheme;
            this.project = project;
            this.file = file;
        }

        /** Foreground RGB of a token type, or -1 for the default foreground. */
        int color(com.intellij.psi.tree.IElementType type) {
            if (type == null) return -1;
            Integer cached = colors.get(type);
            if (cached != null) return cached;
            SyntaxHighlighter highlighter = highlighters.computeIfAbsent(type.getLanguage(), language -> {
                SyntaxHighlighter byLanguage = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, file);
                return byLanguage != null || file == null ? byLanguage
                        : SyntaxHighlighterFactory.getSyntaxHighlighter(file.getFileType(), project, file);
            });
            int rgb = -1;
            if (highlighter != null) {
                // Later keys are layered over earlier ones, as in the editor.
                for (TextAttributesKey key : highlighter.getTokenHighlights(type)) {
                    TextAttributes attributes = scheme.getAttributes(key);
                    if (attributes != null && attributes.getForegroundColor() != null) {
                        rgb = attributes.getForegroundColor().getRGB();
                    }
                }
            }
            colors.put(type, rgb);
            return rgb;
        }
    }

    private static List<RasterRenderer.Token> tokens(EditorHighlighter highlighter, TokenColors palette, int length,
                                                     int[] starts, int[] ends, int samples) {
        List<RasterRenderer.Token> tokens = new ArrayList<>();
        int previousStart = -1;
        for (int i = 0; i < samples && tokens.size() < MAX_TOKENS; i++) {
            if ((i & 63) == 0) ProgressManager.checkCanceled();
            if (ends[i] <= starts[i]) continue;
            HighlighterIterator iterator = highlighter.createIterator(starts[i]);
            // Only the first MAX_COLUMNS characters are drawn: a minified megabyte line must not be walked.
            int drawnEnd = Math.min(ends[i], starts[i] + RasterRenderer.MAX_COLUMNS);
            int steps = 0;
            while (!iterator.atEnd() && iterator.getStart() < drawnEnd && tokens.size() < MAX_TOKENS) {
                if ((++steps & 255) == 0) ProgressManager.checkCanceled();
                int start = Math.clamp(iterator.getStart(), 0, length);
                int end = Math.clamp(iterator.getEnd(), start, length);
                int color = palette.color(iterator.getTokenType());
                if (color != -1 && start >= previousStart) {
                    tokens.add(new RasterRenderer.Token(start, end, color));
                    previousStart = start;
                }
                iterator.advance();
            }
        }
        return tokens;
    }

    /** Overlay colors: the editor color scheme by default, or custom picker values from settings. */
    private record Palette(Color background, Color viewport, Color viewportBorder, Color caretLine,
                           Color selection, Color markerLabel, Color declarationLabel, Color separator) {}

    private Palette palette() {
        var s = settings();
        EditorColorsScheme scheme = editor.getColorsScheme();
        Color background = scheme.getDefaultBackground();
        Color foreground = scheme.getDefaultForeground();
        Color selection = schemeColor(scheme, EditorColors.SELECTION_BACKGROUND_COLOR, foreground);
        Color caret = schemeColor(scheme, EditorColors.CARET_COLOR, foreground);
        var todo = scheme.getAttributes(CodeInsightColors.TODO_DEFAULT_ATTRIBUTES);
        Color marker = todo != null && todo.getForegroundColor() != null ? todo.getForegroundColor() : caret;
        var function = scheme.getAttributes(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
        Color declaration = function != null && function.getForegroundColor() != null
                ? function.getForegroundColor() : foreground;
        Color guide = schemeColor(scheme, EditorColors.INDENT_GUIDE_COLOR, foreground);
        return new Palette(background,
                CodeScrollSettings.color(s.viewportColor, selection, s.viewportOpacity),
                CodeScrollSettings.color(s.viewportBorderColor, selection, s.viewportBorderOpacity),
                CodeScrollSettings.color(s.caretLineColor, caret, 120),
                CodeScrollSettings.color(s.selectionColor, selection, 110),
                CodeScrollSettings.color(s.markerLabelColor, marker, 255),
                CodeScrollSettings.color(s.declarationLabelColor, declaration, 255),
                alpha(guide, 160));
    }

    private static Color schemeColor(EditorColorsScheme scheme, ColorKey key, Color fallback) {
        Color color = scheme.getColor(key);
        return color != null ? color : fallback;
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (disposed || editor.isDisposed()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            var s = settings();
            Palette palette = palette();
            g.setColor(palette.background());
            g.fillRect(0, 0, getWidth(), getHeight());
            MapGeometry geometry = geometry();
            Rectangle viewport = viewport(geometry);
            if (collapsed()) {
                g.setColor(palette.viewportBorder());
                g.fillRect(getWidth() / 2 - 1, viewport.y, 3, viewport.height);
                return;
            }
            if (frame != null && frame.overview() != null) paintOverview(g, geometry, frame.overview());
            if (frame != null) {
                BufferedImage image = frame.image();
                g.drawImage(image, 0, (int) Math.round(frame.geometry().origin() - geometry.origin()),
                        (int) Math.round(image.getWidth() / frame.rasterScale()),
                        (int) Math.round(image.getHeight() / frame.rasterScale()), null);
                // Moved to a screen with another scale: render sharp for it.
                if (frame.rasterScale() != rasterScale()) requestScrollRender();
            }
            var selection = editor.getSelectionModel();
            if (selection.hasSelection()) {
                int top = editor.getDocument().getLineNumber(selection.getSelectionStart());
                int bottom = editor.getDocument().getLineNumber(selection.getSelectionEnd()) + 1;
                g.setColor(palette.selection());
                band(g, geometry, top, bottom, 0, getWidth());
            }
            paintMarkup(g, geometry, editor.getMarkupModel(), palette.caretLine());
            if (editor instanceof EditorEx ex) paintMarkup(g, geometry, ex.getFilteredDocumentMarkupModel(), palette.caretLine());
            // The frame brightens while hovered and more while dragged, so its state is visible.
            Color fill = palette.viewport();
            double boost = dragging ? 2.3 : hoverFrame ? 1.6 : 1;
            g.setColor(alpha(fill, (int) Math.min(255, Math.round(fill.getAlpha() * boost))));
            g.fill(viewport);
            if (s.viewportBorderWidth > 0) {
                g.setColor(palette.viewportBorder());
                int border = Math.min(s.viewportBorderWidth, Math.max(1, viewport.height / 2));
                g.fillRect(viewport.x, viewport.y, viewport.width, border);
                g.fillRect(viewport.x, viewport.y + viewport.height - border, viewport.width, border);
                g.fillRect(viewport.x, viewport.y, border, viewport.height);
                g.fillRect(viewport.x + viewport.width - border, viewport.y, border, viewport.height);
            }
            g.setColor(palette.caretLine());
            int caretLine = editor.getCaretModel().getLogicalPosition().line;
            band(g, geometry, caretLine, caretLine + 1, 0, getWidth());
            if (frame != null) paintLabels(g, geometry, palette);
            if (mouseInside && s.showQuickHide) paintQuickHide(g, palette.background(), palette.declarationLabel());
            g.setColor(palette.separator());
            int edge = s.leftAligned ? getWidth() - 1 : 0;
            g.drawLine(edge, 0, edge, getHeight());
        } finally { g.dispose(); }
    }

    /** Stretches the part of the whole-document raster that the panel shows; the precise window paints over it. */
    private void paintOverview(Graphics2D g, MapGeometry geometry, Overview overview) {
        BufferedImage image = overview.image();
        double topLine = geometry.origin() / geometry.scale();
        double bottomLine = (geometry.origin() + getHeight()) / geometry.scale();
        int sourceTop = Math.clamp((int) Math.floor(topLine * overview.rowsPerLine()), 0, image.getHeight() - 1);
        int sourceBottom = Math.clamp((int) Math.ceil(bottomLine * overview.rowsPerLine()), sourceTop + 1, image.getHeight());
        int targetTop = (int) Math.round(sourceTop / overview.rowsPerLine() * geometry.scale() - geometry.origin());
        int targetBottom = (int) Math.round(sourceBottom / overview.rowsPerLine() * geometry.scale() - geometry.origin());
        Graphics2D stretched = (Graphics2D) g.create();
        try {
            stretched.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            stretched.drawImage(image, 0, targetTop, (int) Math.round(image.getWidth() / overview.rasterScale()), targetBottom,
                    0, sourceTop, image.getWidth(), sourceBottom, null);
        } finally {
            stretched.dispose();
        }
    }

    private void paintLabels(Graphics2D g, MapGeometry geometry, Palette palette) {
        int size = settings().markerFontSize;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font marker = new Font(Font.MONOSPACED, Font.PLAIN, size);
        Font declaration = marker.deriveFont(Font.BOLD);
        int height = size + 2;
        Color plate = alpha(palette.background(), 215);
        List<Label> labels = frame.labels();
        double[] tops = new double[labels.size()];
        for (int i = 0; i < tops.length; i++) tops[i] = labels.get(i).line() * geometry.scale();
        // Overlaps are resolved in document coordinates, so scrolling never changes which labels show.
        boolean[] visible = LabelLayout.visible(tops, height + 1);
        for (int i = 0; i < tops.length; i++) {
            if (!visible[i]) continue;
            Label label = labels.get(i);
            int y = (int) Math.round(tops[i] - geometry.origin());
            // A label partly outside the panel is clipped by the panel edge like code text.
            if (y + height <= 0 || y >= getHeight()) continue;
            g.setColor(plate);
            g.fillRect(3, y, getWidth() - 9, height);
            g.setFont(label.declaration() ? declaration : marker);
            g.setColor(label.declaration() ? palette.declarationLabel() : palette.markerLabel());
            g.drawString(label.text(), 4, y + size);
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

    /** The editor's visible area at map scale, clamped to the document (see {@link MapGeometry}). */
    private Rectangle viewport(MapGeometry geometry) {
        double span = spanLines();
        int top = (int) Math.floor(geometry.frameTop(topLine(), span));
        int height = (int) Math.ceil(geometry.frameHeight(span));
        return new Rectangle(0, top, getWidth(), Math.max(1, height));
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
                        if (MarkupClassifier.isError(highlighter.getLayer()) ? !s.showErrors : !s.showWarnings) continue;
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
        if (dragging) {
            dragStartY = event.getY();
            dragStartFrameTop = geometry.frameTop(topLine(), spanLines()) + geometry.origin();
            repaint();
            return;
        }
        switch (settings().clickTiming) {
            case DOWN -> {
                // The frame lands centered under the pointer; keep pulling it without releasing.
                dragStartFrameTop = click(event);
                dragStartY = event.getY();
                dragging = true;
                repaint();
            }
            case UP -> pendingClick = true;
            case NONE -> { }
        }
    }

    private void released(MouseEvent event) {
        if (resizing) CodeScrollManager.getInstance().relayoutAll();
        if (pendingClick && contains(event.getPoint())) click(event);
        pendingClick = resizing = dragging = false;
        repaint();
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
            dragFrame(event.getY());
        }
    }

    /**
     * Moves the editor so the frame follows the pointer. When the map scrolls, the frame covers the
     * whole document while travelling only the panel height, so pointer movement is scaled accordingly.
     */
    private void dragFrame(int y) {
        MapGeometry geometry = geometry();
        double frame = geometry.frameHeight(spanLines());
        scrollToFrameTop(dragStartFrameTop + documentDelta(geometry, frame, y - dragStartY));
    }

    /**
     * Converts a pointer movement on the panel into a movement of the frame in map-document pixels. When the
     * map scrolls, the frame covers the whole document while travelling only the panel height.
     */
    private double documentDelta(MapGeometry geometry, double frame, double panelDelta) {
        double documentHeight = geometry.documentHeight();
        double maxFrameTop = Math.max(0, documentHeight - frame);
        double travel = Math.min(getHeight(), documentHeight) - frame;
        return documentHeight <= getHeight() || travel <= 0 ? panelDelta : panelDelta * maxFrameTop / travel;
    }

    /** Scrolls the editor so the frame's top is at {@code frameTop} map-document pixels. */
    private void scrollToFrameTop(double frameTop) {
        MapGeometry geometry = geometry();
        double frame = geometry.frameHeight(spanLines());
        double maxFrameTop = Math.max(0, geometry.documentHeight() - frame);
        double line = Math.clamp(frameTop, 0, maxFrameTop) / geometry.scale();
        int whole = Math.clamp((int) Math.floor(line), 0, Math.max(0, editor.getDocument().getLineCount() - 1));
        int editorY = editor.logicalPositionToXY(new LogicalPosition(whole, 0)).y
                + (int) Math.round((line - whole) * editor.getLineHeight());
        editor.getScrollingModel().disableAnimation();
        try { editor.getScrollingModel().scrollVertically(Math.max(0, editorY)); }
        finally { editor.getScrollingModel().enableAnimation(); }
    }

    private void moved(MouseEvent event) {
        lastMouseY = event.getY();
        boolean overFrame = viewport(geometry()).contains(event.getPoint());
        if (overFrame != hoverFrame) {
            hoverFrame = overFrame;
            repaint();
        }
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
        hoverFrame = false;
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

    /**
     * Jumps so that the frame is centered under the pointer, as if it had been grabbed by its middle, and
     * returns the new frame top in map-document pixels. In proportional mode the map scrolls with the
     * editor, so the target is solved through the same mapping that dragging uses, not by centering the
     * clicked line in the editor (which left the frame above or below the pointer).
     */
    private double click(MouseEvent event) {
        MapGeometry geometry = geometry();
        double frame = geometry.frameHeight(spanLines());
        double frameTop = documentDelta(geometry, frame, event.getY() - frame / 2);
        scrollToFrameTop(frameTop);
        MapGeometry moved = geometry();
        if (!settings().scrollOnly) {
            // With "click by mouse position", the column under the pointer is used as well as the line.
            int column = settings().clickByMousePosition ? Math.max(0, event.getX() - 4) : 0;
            placeCaret(moved.line(event.getY()), column, event.isShiftDown());
        }
        return moved.frameTop(topLine(), spanLines()) + moved.origin();
    }

    /** Moves the caret (expanding a fold that hides it) without scrolling; the click already scrolled. */
    private void placeCaret(int line, int column, boolean extendSelection) {
        if (disposed || editor.isDisposed()) return;
        var document = editor.getDocument();
        int bounded = Math.clamp(line, 0, Math.max(0, document.getLineCount() - 1));
        int offset = Math.min(document.getLineEndOffset(bounded),
                editor.logicalPositionToOffset(new LogicalPosition(bounded, column)));
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
        JCheckBoxMenuItem diagnostics = new JCheckBoxMenuItem("Show Errors and Warnings", settings().showDiagnostics);
        diagnostics.addActionListener(e -> {
            settings().showDiagnostics = diagnostics.isSelected();
            CodeScrollManager.getInstance().repaintAll();
        });
        menu.add(diagnostics);
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
        for (Timer timer : new Timer[]{debounce, markupRepaint, revealTimer, collapseTimer, previewTimer, scrollRender}) {
            timer.stop();
        }
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

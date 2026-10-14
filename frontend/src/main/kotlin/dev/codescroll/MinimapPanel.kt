package dev.codescroll

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.Future
import javax.swing.JCheckBoxMenuItem
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

class MinimapPanel(private val editor: Editor) : JPanel(), Disposable {
    private data class Label(val line: Int, val text: String, val declaration: Boolean)

    /** [geometry] is in panel (logical) pixels; the image has [rasterScale] device pixels per one. */
    private data class Frame(
        val image: BufferedImage, val geometry: MapGeometry, val rasterScale: Double,
        val labels: List<Label>, val stamp: Long, val overview: Overview?,
    )

    /** Whole-document raster; [rowsPerLine] is in its own device rows. */
    private data class Overview(
        val image: BufferedImage, val rowsPerLine: Double, val rasterScale: Double, val width: Int, val stamp: Long,
    )

    /** Overlay colors: the editor color scheme by default, or custom picker values from settings. */
    private data class Palette(
        val background: Color, val viewport: Color, val viewportBorder: Color, val caretLine: Color,
        val selection: Color, val markerLabel: Color, val declarationLabel: Color, val separator: Color,
    )

    private val preview = PreviewPopup(editor)
    private val debounce = timer(settings.debounceMs) { submitRender() }
    private val markupRepaint = timer(80) { repaint() }
    private val revealTimer = timer(max(10, settings.hoverDelayMs)) { setRevealed(true) }
    private val collapseTimer = timer(400) { if (!mouseInside && !dragging && !resizing) setRevealed(false) }
    private val previewTimer = timer(max(10, settings.hoverDelayMs)) { showPreview() }
    private val scrollRender = timer(30) { submitRender() }
    private var renderTask: Future<*>? = null
    /** A scroll needed a new raster while one was still being rendered; render again when it lands. */
    private var renderAgain = false
    /** Labels depend only on text and label settings, not on the scroll position: reuse them. */
    private var cachedLabels: List<Label>? = null
    private var cachedLabelStamp = -1L
    private var cachedLabelKey: String? = null
    private var frame: Frame? = null
    private var generation = 0L
    private var disposed = false
    private var dragging = false
    private var resizing = false
    private var pendingClick = false
    private var mouseInside = false
    private var revealed = false
    private var lastMouseY = 0
    private var hoverFrame = false
    private var dragStartY = 0
    private var dragStartFrameTop = 0.0
    private var initialX = 0
    private var initialWidth = 0
    private var oldScrollbarPolicy = -1
    private var scrollbarHidden = false
    private val editorResize = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = updateWidth()
    }

    private val settings: CodeScrollSettings.State get() = CodeScrollSettings.current

    init {
        Disposer.register(this, preview)
        isOpaque = true
        isFocusable = false
        updateWidth()
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = invalidateMap()
        }, this)
        editor.scrollingModel.addVisibleAreaListener({
            // The raster covers more than the panel; render the next window before the shown part leaves it.
            frame?.let { if (!covers(it.geometry, geometry(), height / 2.0)) requestScrollRender() }
            repaint()
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = repaint()
        }, this)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = repaint()
        }, this)
        // Diagnostics, VCS ranges, bookmarks and search results arrive without document events.
        listenToMarkup(editor.markupModel)
        listenToMarkup(DocumentMarkupModel.forDocument(editor.document, editor.project, true))
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { invalidateMap() })
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                updateWidth()
                invalidateMap()
            }

            override fun componentShown(event: ComponentEvent) = invalidateMap()
            override fun componentHidden(event: ComponentEvent) = dropRaster()
        })
        // Tab switches change showing state without component events: refresh a stale map when shown again,
        // and drop the raster of hidden tabs so background editors do not each hold an image.
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() == 0L || disposed) return@addHierarchyListener
            if (isShowing) {
                if (inDialog()) {
                    CodeScrollManager.getInstance().hide(editor)
                    return@addHierarchyListener
                }
                val current = frame
                if (current == null || current.stamp != editor.document.modificationStamp) invalidateMap()
            } else {
                dropRaster()
            }
        }
        editor.component.addComponentListener(editorResize)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = pressed(event)
            override fun mouseReleased(event: MouseEvent) = released(event)
            override fun mouseDragged(event: MouseEvent) = dragged(event)
            override fun mouseMoved(event: MouseEvent) = moved(event)
            override fun mouseEntered(event: MouseEvent) = entered()
            override fun mouseExited(event: MouseEvent) = exited()
            override fun mouseWheelMoved(event: MouseWheelEvent) = wheel(event)
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        if (settings.hideScrollbar && editor is EditorEx) oldScrollbarPolicy = editor.scrollPane.verticalScrollBarPolicy
        // Highlighting and markup can arrive late (from the backend in Remote Development): report after a delay.
        if (Diagnostics.automatic) {
            var reports = 0
            Timer(20_000) { event ->
                if (disposed || editor.isDisposed || ++reports > 6) (event.source as Timer).stop()
                else Diagnostics.log(editor)
            }.apply { initialDelay = 15_000 }.start()
        }
        invalidateMap()
    }

    private fun dropRaster() {
        generation++
        debounce.stop()
        renderTask?.cancel(true)
        frame = null
        preview.hide()
    }

    private fun listenToMarkup(model: MarkupModel?) {
        (model as? MarkupModelEx)?.addMarkupModelListener(this, object : MarkupModelListener {
            override fun afterAdded(highlighter: RangeHighlighterEx) = scheduleMarkupRepaint()
            override fun afterRemoved(highlighter: RangeHighlighterEx) = scheduleMarkupRepaint()
            override fun attributesChanged(
                highlighter: RangeHighlighterEx, renderersChanged: Boolean, fontStyleOrColorChanged: Boolean,
            ) = scheduleMarkupRepaint()
        })
    }

    /** Highlighting passes add hundreds of highlighters at once; coalesce them into one repaint. */
    private fun scheduleMarkupRepaint() {
        if (!SwingUtilities.isEventDispatchThread()) return SwingUtilities.invokeLater(::scheduleMarkupRepaint)
        if (!disposed && !markupRepaint.isRunning) markupRepaint.restart()
    }

    /** Small editors in modal dialogs (settings previews, code style samples) get no minimap; diffs do. */
    private fun inDialog() =
        editor.editorKind != EditorKind.DIFF && SwingUtilities.getWindowAncestor(editor.component) is JDialog

    private fun collapsed() = settings.hoverOnly && !revealed && !dragging && !resizing

    fun updateWidth() {
        val width = targetWidth()
        if (preferredSize.width != width) {
            preferredSize = Dimension(width, 0)
            revalidate()
        }
    }

    private fun targetWidth(): Int {
        if (collapsed()) return COLLAPSED_WIDTH
        var width = settings.widthFor(editor.editorKind)
        val editorWidth = editor.component.width
        if (settings.autoWidth && !resizing && editorWidth > 0) width = min(width, max(40, editorWidth / 5))
        return width
    }

    private fun setRevealed(value: Boolean) {
        if (disposed || revealed == value) return
        revealed = value
        if (!value) preview.hide()
        updateWidth()
        invalidateMap()
    }

    /** First visible logical line, fractional when the editor is scrolled to the middle of a line. */
    private fun topLine(): Double {
        val area = editor.scrollingModel.visibleArea
        val line = editor.xyToLogicalPosition(Point(0, area.y)).line
        return line + (area.y - editor.logicalPositionToXY(LogicalPosition(line, 0)).y) / max(1, editor.lineHeight).toDouble()
    }

    /**
     * How many logical lines the visible area spans. The map draws logical lines, so folded regions, inlays
     * and soft wraps must be measured in them too, not as visible height / line height.
     */
    private fun spanLines(): Double {
        val area = editor.scrollingModel.visibleArea
        val y = area.y + area.height
        val line = editor.xyToLogicalPosition(Point(0, y)).line
        val fraction = ((y - editor.logicalPositionToXY(LogicalPosition(line, 0)).y) / max(1, editor.lineHeight).toDouble())
            .coerceIn(0.0, 1.0)
        return max(1.0, line + fraction - topLine())
    }

    /** Live geometry for the current scroll position; cheap, computed on every paint and event. */
    private fun geometry(): MapGeometry = MapGeometry.forScroll(
        editor.document.lineCount, height, settings.fit, settings.fill, settings.pixelsPerLine, topLine(), spanLines(),
    )

    /** Device pixels per panel pixel: 2 on Retina, 1 on standard screens. */
    private fun rasterScale(): Double = (graphicsConfiguration?.defaultTransform?.scaleY ?: 1.0).coerceIn(1.0, 3.0)

    /**
     * Whether a rendered raster holds every map row the panel shows at [live], plus [margin] rows beyond each
     * edge (the margin is waived at the document's own start and end).
     */
    private fun covers(raster: MapGeometry, live: MapGeometry, margin: Double): Boolean {
        if (raster.scale != live.scale || raster.lines != live.lines) return false
        val top = max(0.0, live.origin - margin)
        val bottom = min(live.origin + height + margin, live.documentHeight)
        return top >= raster.origin - 0.5 && bottom <= raster.origin + raster.height + 0.5
    }

    /**
     * Scrolling must not cancel a render in flight: with continuous scrolling each request would cancel the
     * previous one and no raster would ever finish. Queue one more render instead.
     */
    private fun requestScrollRender() {
        if (renderTask?.isDone == false) renderAgain = true
        else if (!scrollRender.isRunning) scrollRender.restart()
    }

    private fun inLineRange() = editor.document.lineCount in settings.minLines..settings.maxLines

    /** Console/untyped editors can keep a navigable but text-free map, as configured. */
    private fun textless() = (editor.editorKind == EditorKind.CONSOLE && settings.emptyConsole) ||
        (editor.editorKind == EditorKind.UNTYPED && settings.emptyUntyped)

    private fun invalidateMap() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            return ApplicationManager.getApplication().invokeLater(::invalidateMap)
        }
        if (disposed || editor.isDisposed) return
        val inRange = inLineRange()
        val shown = inRange || settings.outOfRangeEmpty
        if (!inRange) frame = null
        if (oldScrollbarPolicy != -1 && editor is EditorEx && scrollbarHidden != shown) {
            editor.scrollPane.verticalScrollBarPolicy = if (shown) ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER else oldScrollbarPolicy
            scrollbarHidden = shown
        }
        if (isVisible != shown) {
            isVisible = shown
            editor.component.revalidate()
        }
        generation++
        renderTask?.cancel(true)
        if (inRange) {
            debounce.initialDelay = settings.debounceMs
            debounce.restart()
        }
        repaint()
    }

    private fun submitRender() {
        if (disposed || editor.isDisposed || width < 40 || height <= 0 || !isShowing) return
        val s = settings
        if (!inLineRange()) {
            frame = null
            repaint()
            return
        }
        // Render a window up to the raster limit around the shown part, so scrolling just moves the image.
        // The raster uses device pixels, so HiDPI screens get a sharp map instead of an upscaled one.
        val rasterScale = rasterScale()
        val live = geometry()
        val documentHeight = live.documentHeight
        val maxWindow = (RasterRenderer.MAX_HEIGHT / rasterScale).toInt()
        val windowHeight = min(maxWindow.toDouble(), max(1.0, ceil(documentHeight))).toInt()
        val windowOrigin = if (documentHeight <= maxWindow) 0.0
        else (live.origin - (windowHeight - height) / 2.0).coerceIn(0.0, documentHeight - windowHeight)
        val geometry = live.window(windowHeight, floor(windowOrigin))
        val device = MapGeometry(
            geometry.lines, ceil(windowHeight * rasterScale).toInt(), geometry.scale * rasterScale, geometry.origin * rasterScale,
        )
        val charWidth = max(1, rasterScale.roundToInt())
        val rasterWidth = min(RasterRenderer.MAX_WIDTH, (width * rasterScale).roundToInt())
        val scheme = editor.colorsScheme
        val foreground = scheme.defaultForeground.rgb
        val background = scheme.defaultBackground.rgb
        val textless = textless()
        val accurate = s.renderStyle == CodeScrollSettings.RenderStyle.ACCURATE
        val intensity = s.textIntensity
        // The editor's incremental highlighter already holds lexer state for the whole document.
        val highlighter = if (s.syntaxColors) (editor as? EditorEx)?.highlighter else null
        val project = editor.project
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val labels = !textless && (s.showMarkers || s.showMethods)
        val markdown = MapRaster.isMarkdown(file)
        val hashComments = MapRaster.hashComments(file)
        val words = if (s.showMarkers) s.markerWords else ""
        val regex = if (s.showMarkers) s.markerRegex else ""
        val methods = s.showMethods
        val methodNames = s.methodNames
        val methodAnnotations = s.methodAnnotations
        val labelKey = listOf(labels, words, regex, methods, methodNames, methodAnnotations, markdown, hashComments)
            .joinToString("\u0000")
        val reusableLabels = if (labelKey == cachedLabelKey) cachedLabels else null
        val reusableStamp = cachedLabelStamp
        val reusableOverview = frame?.overview
        val request = generation
        renderTask = ReadAction.nonBlocking<Frame> {
            val document = editor.document
            val text = document.immutableCharSequence
            val stamp = document.modificationStamp
            val colors = highlighter?.let { MapRaster.TokenColors(scheme, project, file) }
            val image = MapRaster.rasterize(
                document, text, device, rasterWidth, charWidth, highlighter, colors, foreground, background,
                accurate, intensity, textless,
            )
            // Long documents also get a coarse raster of the whole file, drawn wherever the precise window does
            // not reach yet (fast drags), so the map never shows blank stretches while catching up.
            val overview = when {
                textless || documentHeight <= maxWindow -> null
                reusableOverview != null && reusableOverview.stamp == stamp && reusableOverview.width == rasterWidth &&
                    reusableOverview.rasterScale == rasterScale -> reusableOverview
                else -> {
                    val rows = RasterRenderer.MAX_HEIGHT
                    val whole = MapGeometry(geometry.lines, rows, rows.toDouble() / geometry.lines, 0.0)
                    Overview(
                        MapRaster.rasterize(
                            document, text, whole, rasterWidth, charWidth, highlighter, colors, foreground, background,
                            accurate, intensity, false,
                        ),
                        whole.scale, rasterScale, rasterWidth, stamp,
                    )
                }
            }
            val found = when {
                reusableLabels != null && reusableStamp == stamp -> reusableLabels
                !labels -> emptyList()
                else -> MarkerScanner.scan(
                    text, words, methods, 2000, methodNames, methodAnnotations, regex, markdown, hashComments,
                    ProgressManager::checkCanceled,
                ).map { Label(document.getLineNumber(it.offset), it.label, it.declaration) }
            }
            Frame(image, geometry, rasterScale, found, stamp, overview)
        }.expireWith(this).coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { result ->
                if (disposed || editor.isDisposed || request != generation ||
                    result.stamp != editor.document.modificationStamp
                ) return@finishOnUiThread
                frame = result
                cachedLabels = result.labels
                cachedLabelStamp = result.stamp
                cachedLabelKey = labelKey
                repaint()
                if (renderAgain) {
                    renderAgain = false
                    if (!covers(result.geometry, geometry(), height / 2.0)) scrollRender.restart()
                }
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun palette(): Palette {
        val s = settings
        val scheme = editor.colorsScheme
        val background = scheme.defaultBackground
        val foreground = scheme.defaultForeground
        val selection = scheme.color(EditorColors.SELECTION_BACKGROUND_COLOR, foreground)
        val caret = scheme.color(EditorColors.CARET_COLOR, foreground)
        val marker = scheme.getAttributes(CodeInsightColors.TODO_DEFAULT_ATTRIBUTES)?.foregroundColor ?: caret
        val declaration = scheme.getAttributes(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)?.foregroundColor ?: foreground
        val guide = scheme.color(EditorColors.INDENT_GUIDE_COLOR, foreground)
        return Palette(
            background,
            CodeScrollSettings.color(s.viewportColor, selection, s.viewportOpacity),
            CodeScrollSettings.color(s.viewportBorderColor, selection, s.viewportBorderOpacity),
            CodeScrollSettings.color(s.caretLineColor, caret, 120),
            CodeScrollSettings.color(s.selectionColor, selection, 110),
            CodeScrollSettings.color(s.markerLabelColor, marker, 255),
            CodeScrollSettings.color(s.declarationLabelColor, declaration, 255),
            guide.withAlpha(160),
        )
    }

    override fun paintComponent(graphics: Graphics) {
        if (disposed || editor.isDisposed) return
        val g = graphics.create() as Graphics2D
        try {
            val s = settings
            val palette = palette()
            g.color = palette.background
            g.fillRect(0, 0, width, height)
            val geometry = geometry()
            val viewport = viewport(geometry)
            if (collapsed()) {
                g.color = palette.viewportBorder
                g.fillRect(width / 2 - 1, viewport.y, 3, viewport.height)
                return
            }
            val current = frame
            current?.overview?.let { paintOverview(g, geometry, it) }
            if (current != null) {
                val image = current.image
                g.drawImage(
                    image, 0, (current.geometry.origin - geometry.origin).roundToInt(),
                    (image.width / current.rasterScale).roundToInt(), (image.height / current.rasterScale).roundToInt(), null,
                )
                // Moved to a screen with another scale: render sharp for it.
                if (current.rasterScale != rasterScale()) requestScrollRender()
            }
            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                g.color = palette.selection
                band(g, geometry, editor.document.getLineNumber(selection.selectionStart),
                    editor.document.getLineNumber(selection.selectionEnd) + 1, 0, width)
            }
            paintMarkup(g, geometry, editor.markupModel, palette.caretLine)
            if (editor is EditorEx) paintMarkup(g, geometry, editor.filteredDocumentMarkupModel, palette.caretLine)
            // The frame brightens while hovered and more while dragged, so its state is visible.
            val boost = if (dragging) 2.3 else if (hoverFrame) 1.6 else 1.0
            g.color = palette.viewport.withAlpha(min(255, (palette.viewport.alpha * boost).roundToInt()))
            g.fill(viewport)
            if (s.viewportBorderWidth > 0) {
                g.color = palette.viewportBorder
                val border = min(s.viewportBorderWidth, max(1, viewport.height / 2))
                g.fillRect(viewport.x, viewport.y, viewport.width, border)
                g.fillRect(viewport.x, viewport.y + viewport.height - border, viewport.width, border)
                g.fillRect(viewport.x, viewport.y, border, viewport.height)
                g.fillRect(viewport.x + viewport.width - border, viewport.y, border, viewport.height)
            }
            g.color = palette.caretLine
            val caretLine = editor.caretModel.logicalPosition.line
            band(g, geometry, caretLine, caretLine + 1, 0, width)
            if (current != null) paintLabels(g, geometry, palette, current.labels)
            if (mouseInside && s.showQuickHide) paintQuickHide(g, palette.background, palette.declarationLabel)
            g.color = palette.separator
            val edge = if (s.leftAligned) width - 1 else 0
            g.drawLine(edge, 0, edge, height)
        } finally {
            g.dispose()
        }
    }

    /** Stretches the part of the whole-document raster that the panel shows; the precise window paints over it. */
    private fun paintOverview(g: Graphics2D, geometry: MapGeometry, overview: Overview) {
        val image = overview.image
        val topLine = geometry.origin / geometry.scale
        val bottomLine = (geometry.origin + height) / geometry.scale
        val sourceTop = floor(topLine * overview.rowsPerLine).toInt().coerceIn(0, image.height - 1)
        val sourceBottom = ceil(bottomLine * overview.rowsPerLine).toInt().coerceIn(sourceTop + 1, image.height)
        val targetTop = (sourceTop / overview.rowsPerLine * geometry.scale - geometry.origin).roundToInt()
        val targetBottom = (sourceBottom / overview.rowsPerLine * geometry.scale - geometry.origin).roundToInt()
        val stretched = g.create() as Graphics2D
        try {
            stretched.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            stretched.drawImage(
                image, 0, targetTop, (image.width / overview.rasterScale).roundToInt(), targetBottom,
                0, sourceTop, image.width, sourceBottom, null,
            )
        } finally {
            stretched.dispose()
        }
    }

    private fun paintLabels(g: Graphics2D, geometry: MapGeometry, palette: Palette, labels: List<Label>) {
        val size = settings.markerFontSize
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val marker = Font(Font.MONOSPACED, Font.PLAIN, size)
        val declaration = marker.deriveFont(Font.BOLD)
        val labelHeight = size + 2
        val plate = palette.background.withAlpha(215)
        val tops = DoubleArray(labels.size) { labels[it].line * geometry.scale }
        // Overlaps are resolved in document coordinates, so scrolling never changes which labels show.
        val visible = LabelLayout.visible(tops, labelHeight + 1.0)
        for (i in tops.indices) {
            if (!visible[i]) continue
            val label = labels[i]
            val y = (tops[i] - geometry.origin).roundToInt()
            // A label partly outside the panel is clipped by the panel edge like code text.
            if (y + labelHeight <= 0 || y >= height) continue
            g.color = plate
            g.fillRect(3, y, width - 9, labelHeight)
            g.font = if (label.declaration) declaration else marker
            g.color = if (label.declaration) palette.declarationLabel else palette.markerLabel
            g.drawString(label.text, 4, y + size)
        }
    }

    private fun quickHideBounds(): Rectangle {
        val x = if (settings.leftAligned) 2 else width - QUICK_HIDE_SIZE - 2
        return Rectangle(x, 2, QUICK_HIDE_SIZE, QUICK_HIDE_SIZE)
    }

    private fun paintQuickHide(g: Graphics2D, background: Color, foreground: Color) {
        val box = quickHideBounds()
        g.color = background.withAlpha(235)
        g.fill(box)
        g.color = foreground.withAlpha(200)
        g.drawRect(box.x, box.y, box.width - 1, box.height - 1)
        g.drawLine(box.x + 3, box.y + 3, box.x + box.width - 4, box.y + box.height - 4)
        g.drawLine(box.x + box.width - 4, box.y + 3, box.x + 3, box.y + box.height - 4)
    }

    /** The editor's visible area at map scale, clamped to the document (see [MapGeometry]). */
    private fun viewport(geometry: MapGeometry): Rectangle {
        val span = spanLines()
        val top = floor(geometry.frameTop(topLine(), span)).toInt()
        val frameHeight = ceil(geometry.frameHeight(span)).toInt()
        return Rectangle(0, top, width, max(1, frameHeight))
    }

    private fun paintMarkup(g: Graphics2D, geometry: MapGeometry, model: MarkupModel, accent: Color) {
        val extended = model as? MarkupModelEx ?: return
        val s = settings
        val document = editor.document
        if (document.lineCount == 0) return
        val start = document.getLineStartOffset(min(document.lineCount - 1, geometry.line(0.0)))
        val end = document.getLineEndOffset(min(document.lineCount - 1, geometry.line(height.toDouble())))
        val scheme = editor.colorsScheme
        // VCS ranges sit on the editor-facing edge, like the gutter; diagnostics on the far edge.
        val nearEdge = if (s.leftAligned) width - 3 else 0
        val farEdge = if (s.leftAligned) 0 else width - 5
        val deadline = System.nanoTime() + 2_000_000
        var painted = 0
        extended.overlappingIterator(start, end).use { iterator ->
            while (iterator.hasNext() && painted < 2000 && System.nanoTime() < deadline) {
                val highlighter = iterator.next()
                if (!highlighter.isValid) continue
                val stripe = highlighter.getErrorStripeMarkColor(scheme)
                val kind = MarkupClassifier.classify(
                    highlighter.textAttributesKey?.externalName,
                    (highlighter as? RangeHighlighterEx)?.isThinErrorStripeMark == true,
                    highlighter.lineMarkerRenderer?.javaClass?.name,
                    highlighter.gutterIconRenderer?.javaClass?.name,
                    stripe != null,
                    highlighter.gutterIconRenderer?.let { "${it.tooltipText} ${it.accessibleName}" },
                )
                val background = if (kind == MarkupClassifier.Kind.SEARCH || kind == MarkupClassifier.Kind.OTHER) {
                    highlighter.getTextAttributes(scheme)?.backgroundColor
                } else null
                val top = document.getLineNumber(min(document.textLength, highlighter.startOffset))
                val bottom = document.getLineNumber(min(document.textLength, highlighter.endOffset)) + 1
                when (kind) {
                    MarkupClassifier.Kind.SEARCH -> {
                        val color = background ?: stripe
                        if (!s.showSearch || color == null) continue
                        g.color = color.withAlpha(150)
                        band(g, geometry, top, bottom, 0, width)
                    }
                    MarkupClassifier.Kind.BOOKMARK -> {
                        if (!s.showBookmarks) continue
                        g.color = stripe ?: accent
                        val y = geometry.y(top.toDouble()).toInt()
                        g.fillRect(0, y, width, 2)
                        val tip = if (s.leftAligned) width - 1 else 0
                        val direction = if (s.leftAligned) -1 else 1
                        g.fillPolygon(intArrayOf(tip, tip + 6 * direction, tip), intArrayOf(y - 4, y + 1, y + 6), 3)
                    }
                    MarkupClassifier.Kind.VCS -> {
                        if (!s.showVcs) continue
                        // JetBrains Client's VCS ranges may carry no stripe color; use the scheme's change color.
                        g.color = stripe ?: scheme.getColor(EditorColors.MODIFIED_LINES_COLOR) ?: continue
                        band(g, geometry, top, bottom, nearEdge, 3)
                    }
                    MarkupClassifier.Kind.DIAGNOSTIC -> {
                        if (!s.showDiagnostics || stripe == null) continue
                        if (if (MarkupClassifier.isError(highlighter.layer)) !s.showErrors else !s.showWarnings) continue
                        g.color = if (s.fullLineDiagnostics) stripe.withAlpha(110) else stripe
                        if (s.fullLineDiagnostics) band(g, geometry, top, bottom, 0, width)
                        else band(g, geometry, top, bottom, farEdge, 4)
                    }
                    MarkupClassifier.Kind.OTHER -> {
                        val color = background ?: stripe ?: continue
                        g.color = if (s.fullLineMarkup) color.withAlpha(90) else color
                        if (s.fullLineMarkup) band(g, geometry, top, bottom, 0, width)
                        else band(g, geometry, top, bottom, farEdge, 4)
                    }
                }
                painted++
            }
        }
    }

    private fun band(g: Graphics2D, geometry: MapGeometry, top: Int, bottom: Int, x: Int, bandWidth: Int) {
        val y = geometry.y(top.toDouble()).toInt()
        g.fillRect(x, y, bandWidth, max(1, ceil(geometry.y(bottom.toDouble()) - y).toInt()))
    }

    private fun onResizeEdge(x: Int) =
        !settings.lockWidth && (if (settings.leftAligned) x > width - RESIZE_EDGE else x < RESIZE_EDGE)

    private fun pressed(event: MouseEvent) {
        previewTimer.stop()
        preview.hide()
        if (popup(event) || !SwingUtilities.isLeftMouseButton(event)) return
        if (collapsed()) return setRevealed(true)
        if (settings.showQuickHide && quickHideBounds().contains(event.point)) {
            // Disposes this panel; nothing below may touch it.
            return CodeScrollManager.getInstance().hide(editor)
        }
        initialX = event.xOnScreen
        initialWidth = settings.widthFor(editor.editorKind)
        resizing = onResizeEdge(event.x)
        if (resizing) return
        val geometry = geometry()
        dragging = viewport(geometry).contains(event.point) && !event.isShiftDown
        if (dragging) {
            dragStartY = event.y
            dragStartFrameTop = geometry.frameTop(topLine(), spanLines()) + geometry.origin
            return repaint()
        }
        when (settings.clickTiming) {
            CodeScrollSettings.ClickTiming.DOWN -> {
                // The frame lands centered under the pointer; keep pulling it without releasing.
                dragStartFrameTop = click(event)
                dragStartY = event.y
                dragging = true
                repaint()
            }
            CodeScrollSettings.ClickTiming.UP -> pendingClick = true
            CodeScrollSettings.ClickTiming.NONE -> Unit
        }
    }

    private fun released(event: MouseEvent) {
        if (resizing) CodeScrollManager.getInstance().relayoutAll()
        if (pendingClick && contains(event.point)) click(event)
        pendingClick = false
        resizing = false
        dragging = false
        repaint()
        if (!mouseInside && settings.hoverOnly) collapseTimer.restart()
        popup(event)
    }

    private fun dragged(event: MouseEvent) {
        if (resizing) {
            val delta = event.xOnScreen - initialX
            settings.setWidthFor(editor.editorKind, initialWidth + if (settings.leftAligned) delta else -delta)
            updateWidth()
            invalidateMap()
        } else if (dragging) {
            val geometry = geometry()
            val frameHeight = geometry.frameHeight(spanLines())
            scrollToFrameTop(dragStartFrameTop + documentDelta(geometry, frameHeight, (event.y - dragStartY).toDouble()))
        }
    }

    /**
     * Converts a pointer movement on the panel into a movement of the frame in map-document pixels. When the
     * map scrolls, the frame covers the whole document while travelling only the panel height.
     */
    private fun documentDelta(geometry: MapGeometry, frame: Double, panelDelta: Double): Double {
        val documentHeight = geometry.documentHeight
        val maxFrameTop = max(0.0, documentHeight - frame)
        val travel = min(height.toDouble(), documentHeight) - frame
        return if (documentHeight <= height || travel <= 0) panelDelta else panelDelta * maxFrameTop / travel
    }

    /** Scrolls the editor so the frame's top is at [frameTop] map-document pixels. */
    private fun scrollToFrameTop(frameTop: Double) {
        val geometry = geometry()
        val maxFrameTop = max(0.0, geometry.documentHeight - geometry.frameHeight(spanLines()))
        val line = frameTop.coerceIn(0.0, maxFrameTop) / geometry.scale
        val whole = floor(line).toInt().coerceIn(0, max(0, editor.document.lineCount - 1))
        val editorY = editor.logicalPositionToXY(LogicalPosition(whole, 0)).y + ((line - whole) * editor.lineHeight).roundToInt()
        editor.scrollingModel.disableAnimation()
        try {
            editor.scrollingModel.scrollVertically(max(0, editorY))
        } finally {
            editor.scrollingModel.enableAnimation()
        }
    }

    private fun moved(event: MouseEvent) {
        lastMouseY = event.y
        val overFrame = viewport(geometry()).contains(event.point)
        if (overFrame != hoverFrame) {
            hoverFrame = overFrame
            repaint()
        }
        cursor = Cursor.getPredefinedCursor(if (onResizeEdge(event.x)) Cursor.E_RESIZE_CURSOR else Cursor.HAND_CURSOR)
        if (preview.isShowing) showPreview()
        else if (settings.showPreview && !collapsed()) {
            previewTimer.initialDelay = max(10, settings.hoverDelayMs)
            previewTimer.restart()
        }
    }

    private fun entered() {
        mouseInside = true
        collapseTimer.stop()
        if (settings.hoverOnly && !revealed) {
            revealTimer.initialDelay = max(10, settings.hoverDelayMs)
            revealTimer.restart()
        }
        repaint()
    }

    private fun exited() {
        mouseInside = false
        hoverFrame = false
        revealTimer.stop()
        previewTimer.stop()
        preview.hide()
        if (settings.hoverOnly && !dragging && !resizing) collapseTimer.restart()
        repaint()
    }

    private fun wheel(event: MouseWheelEvent) {
        val rotation = event.preciseWheelRotation
        if (settings.previewWheel && preview.isShowing) {
            preview.scrollTo(preview.line + sign(rotation).toInt() * max(1, abs(rotation * 3).toInt()))
        } else {
            editor.scrollingModel.scrollVertically(
                max(0, editor.scrollingModel.verticalScrollOffset + (rotation * editor.lineHeight * 3).toInt()),
            )
        }
        event.consume()
    }

    private fun showPreview() {
        if (disposed || editor.isDisposed || !settings.showPreview || dragging || resizing || !mouseInside || collapsed()) return
        preview.show(this, settings.leftAligned, lastMouseY, geometry().line(lastMouseY.toDouble()))
    }

    /**
     * Jumps so that the frame is centered under the pointer, as if it had been grabbed by its middle, and returns
     * the new frame top in map-document pixels. In proportional mode the map scrolls with the editor, so the target
     * is solved through the same mapping that dragging uses, not by centering the clicked line in the editor.
     */
    private fun click(event: MouseEvent): Double {
        val geometry = geometry()
        val frameHeight = geometry.frameHeight(spanLines())
        scrollToFrameTop(documentDelta(geometry, frameHeight, event.y - frameHeight / 2))
        val moved = geometry()
        if (!settings.scrollOnly) {
            // With "click by mouse position", the column under the pointer is used as well as the line.
            val column = if (settings.clickByMousePosition) max(0, event.x - 4) else 0
            placeCaret(moved.line(event.y.toDouble()), column, event.isShiftDown)
        }
        return moved.frameTop(topLine(), spanLines()) + moved.origin
    }

    /** Moves the caret (expanding a fold that hides it) without scrolling; the click already scrolled. */
    private fun placeCaret(line: Int, column: Int, extendSelection: Boolean) {
        if (disposed || editor.isDisposed) return
        val document = editor.document
        val bounded = line.coerceIn(0, max(0, document.lineCount - 1))
        val offset = min(document.getLineEndOffset(bounded), editor.logicalPositionToOffset(LogicalPosition(bounded, column)))
        val selectionModel = editor.selectionModel
        val anchor = if (selectionModel.hasSelection()) selectionModel.leadSelectionOffset else editor.caretModel.offset
        editor.foldingModel.runBatchFoldingOperation {
            editor.foldingModel.allFoldRegions
                .filter { !it.isExpanded && offset > it.startOffset && offset < it.endOffset }
                .forEach { it.isExpanded = true }
        }
        editor.caretModel.moveToOffset(offset)
        if (extendSelection) selectionModel.setSelection(anchor, offset) else selectionModel.removeSelection()
        editor.contentComponent.requestFocusInWindow()
    }

    private fun popup(event: MouseEvent): Boolean {
        if (!event.isPopupTrigger) return false
        val manager = CodeScrollManager.getInstance()
        val menu = JPopupMenu()
        fun toggle(text: String, selected: Boolean, apply: (Boolean) -> Unit) {
            val item = JCheckBoxMenuItem(text, selected)
            item.addActionListener { apply(item.isSelected) }
            menu.add(item)
        }
        fun item(text: String, action: () -> Unit) {
            menu.add(JMenuItem(text).apply { addActionListener { action() } })
        }
        toggle("Fit Whole File", settings.fit) { settings.fit = it; manager.refreshAll() }
        toggle("Fill Editor Height", settings.fill) { settings.fill = it; manager.refreshAll() }
        toggle("Show Errors and Warnings", settings.showDiagnostics) { settings.showDiagnostics = it; manager.repaintAll() }
        toggle("Preserve Caret When Clicking", settings.scrollOnly) { settings.scrollOnly = it }
        menu.addSeparator()
        item("Code Scroll Settings...") {
            ShowSettingsUtil.getInstance().showSettingsDialog(editor.project, CodeScrollConfigurable::class.java)
        }
        item("Hide in This Editor") { manager.hide(editor) }
        item("Hide All Minimaps") { settings.enabled = false; manager.refreshAll() }
        menu.show(this, event.x, event.y)
        return true
    }

    override fun dispose() {
        disposed = true
        generation++
        listOf(debounce, markupRepaint, revealTimer, collapseTimer, previewTimer, scrollRender).forEach { it.stop() }
        renderTask?.cancel(true)
        editor.component.removeComponentListener(editorResize)
        if (oldScrollbarPolicy != -1 && editor is EditorEx && !editor.isDisposed) {
            editor.scrollPane.verticalScrollBarPolicy = oldScrollbarPolicy
        }
        parent?.let {
            it.remove(this)
            it.revalidate()
            it.repaint()
        }
        frame = null
    }

    private companion object {
        /** Width of a hover-only minimap before it is revealed. */
        const val COLLAPSED_WIDTH = 8
        const val RESIZE_EDGE = 5
        const val QUICK_HIDE_SIZE = 12

        fun timer(delay: Int, action: () -> Unit) = Timer(delay) { action() }.apply { isRepeats = false }

        fun Color.withAlpha(alpha: Int) = Color(red, green, blue, alpha)

        fun EditorColorsScheme.color(key: ColorKey, fallback: Color): Color = getColor(key) ?: fallback
    }
}

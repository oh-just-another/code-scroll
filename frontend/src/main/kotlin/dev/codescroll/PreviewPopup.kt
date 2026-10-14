package dev.codescroll

import com.intellij.codeInsight.hint.EditorFragmentComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Hover preview built from the platform's editor fragment component, the same one the IDE shows for
 * error-stripe marks: it paints the real editor, so colors, folding and line numbers are exact.
 */
internal class PreviewPopup(private val owner: Editor) : Disposable {
    private val content = JPanel(BorderLayout())
    private var popup: JBPopup? = null
    private var anchor: JComponent? = null
    private var leftAligned = false
    private var mouseY = 0

    var line = -1
        private set

    val isShowing: Boolean get() = popup?.isVisible == true

    /** Shows or moves the preview beside [anchor], centered on [line]. */
    fun show(anchor: JComponent, leftAligned: Boolean, mouseY: Int, line: Int) {
        if (owner.isDisposed || !anchor.isShowing) return
        this.anchor = anchor
        this.leftAligned = leftAligned
        this.mouseY = mouseY
        if (line != this.line || !isShowing) render(line)
    }

    fun scrollTo(line: Int) {
        if (anchor != null && isShowing) render(line)
    }

    private fun render(target: Int) {
        val anchor = anchor ?: return
        val lastLine = maxOf(0, owner.document.lineCount - 1)
        line = target.coerceIn(0, lastLine)
        val start = maxOf(0, line - CONTEXT)
        val end = minOf(lastLine, line + CONTEXT)
        val gutter = CodeScrollSettings.current.previewLineNumbers
        val fragment = EditorFragmentComponent.createEditorFragmentComponent(owner, start, end + 1, true, gutter)
        content.removeAll()
        content.add(fragment, BorderLayout.CENTER)
        val preferred = fragment.preferredSize
        val width = minOf(preferred.width, maxOf(280, owner.scrollingModel.visibleArea.width * 3 / 5))
        val size = Dimension(width, preferred.height)
        content.preferredSize = size
        val y = (mouseY - size.height / 2).coerceIn(0, maxOf(0, anchor.height - size.height))
        val point = Point(if (leftAligned) anchor.width + 2 else -size.width - 2, y)
        val shown = popup
        if (shown == null || !shown.isVisible) {
            shown?.cancel()
            popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
                .setFocusable(false).setRequestFocus(false).setResizable(false).setMovable(false)
                .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true)
                .createPopup()
                .also { it.show(RelativePoint(anchor, point)) }
        } else {
            shown.size = size
            shown.setLocation(RelativePoint(anchor, point).screenPoint)
            content.revalidate()
            content.repaint()
        }
    }

    fun hide() {
        popup?.cancel()
        popup = null
        line = -1
    }

    override fun dispose() {
        hide()
        content.removeAll()
    }

    private companion object {
        /** Lines shown above and below the hovered line. */
        const val CONTEXT = 7
    }
}

package dev.codescroll

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

@Service(Service.Level.APP)
class CodeScrollManager : Disposable {
    private val panels = HashMap<Editor, MinimapPanel>()
    /** Editors hidden with "Hide in This Editor"; weak so released editors are not retained. */
    private val suppressed: MutableSet<Editor> = Collections.newSetFromMap(WeakHashMap())
    /** Editors shown with "Show in This Editor" although minimaps are off for them globally (#34). */
    private val forced: MutableSet<Editor> = Collections.newSetFromMap(WeakHashMap())
    private var disposed = false

    fun attachLater(editor: Editor) = invokeLater { if (!editor.isDisposed) attach(editor) }

    private fun attach(editor: Editor) {
        if (editor in panels || !eligible(editor)) return
        // No side-panel API exists. Do not replace components owned by the IDE or other plugins.
        val component = editor.component
        val layout = component.layout as? BorderLayout ?: return
        val left = CodeScrollSettings.current.leftAligned
        val side = if (left) BorderLayout.LINE_START else BorderLayout.LINE_END
        val absoluteSide = if (left) BorderLayout.WEST else BorderLayout.EAST
        if (layout.getLayoutComponent(side) != null || layout.getLayoutComponent(absoluteSide) != null) return
        val panel = MinimapPanel(editor)
        panels[editor] = panel
        Disposer.register(this, panel)
        component.add(panel, side)
        // If another plugin later takes the same side slot, BorderLayout stops laying out our panel: step aside.
        val takeover = object : ContainerAdapter() {
            override fun componentAdded(event: ContainerEvent) {
                if (event.child !== panel && layout.getLayoutComponent(side) !== panel &&
                    layout.getLayoutComponent(absoluteSide) !== panel
                ) invokeLater { detach(editor) }
            }
        }
        component.addContainerListener(takeover)
        Disposer.register(panel) { component.removeContainerListener(takeover) }
        component.revalidate()
        component.repaint()
    }

    private fun eligible(editor: Editor): Boolean {
        val s = CodeScrollSettings.current
        if (editor.isDisposed || editor.isOneLineMode || editor in suppressed) return false
        if (editor in forced) return true
        if (!s.enabled) return false
        if (!showsKind(s, editor.editorKind)) return false
        val extension = FileDocumentManager.getInstance().getFile(editor.document)?.extension?.lowercase(Locale.ROOT)
            ?: return true
        return s.excludedExtensions.split(",").none { it.trim().lowercase(Locale.ROOT).removePrefix(".") == extension }
    }

    fun detach(editor: Editor) {
        val panel = panels.remove(editor) ?: return
        Disposer.dispose(panel)
        editor.component.remove(panel)
        editor.component.revalidate()
        editor.component.repaint()
    }

    /** Hides the minimap in one editor until minimaps are toggled on again. */
    fun hide(editor: Editor) {
        suppressed += editor
        detach(editor)
    }

    fun clearHidden() = suppressed.clear()

    fun hasMinimap(editor: Editor) = editor in panels

    /** Shows or hides the minimap in one editor, regardless of the global switch and editor-kind settings. */
    fun toggle(editor: Editor) {
        if (editor in panels) {
            forced -= editor
            hide(editor)
        } else {
            suppressed -= editor
            forced += editor
            attach(editor)
        }
    }

    /** Attaches to editors that do not have a minimap yet, without recreating existing ones. */
    fun attachExisting() = invokeLater { attachAll() }

    /** Recreates every minimap; needed when alignment, eligibility or global switches change. */
    fun refreshAll() = invokeLater {
        panels.keys.toList().forEach(::detach)
        attachAll()
    }

    private fun attachAll() = EditorFactory.getInstance().allEditors.filterNot { it.isDisposed }.forEach(::attach)

    /** Repaints every minimap, for overlay switches that need no new raster. */
    fun repaintAll() = panels.values.forEach { it.repaint() }

    /** Applies a width change to every minimap in place. */
    fun relayoutAll() = panels.values.forEach { it.updateWidth() }

    private fun invokeLater(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater { if (!disposed) action() }

    override fun dispose() {
        disposed = true
        // Child panels remove their own listeners and UI, including on dynamic plugin unload.
        panels.clear()
        suppressed.clear()
        forced.clear()
    }

    companion object {
        @JvmStatic
        fun getInstance(): CodeScrollManager =
            ApplicationManager.getApplication().getService(CodeScrollManager::class.java)

        /** Not an exhaustive `when`: a future EditorKind constant must not throw at runtime. */
        fun showsKind(s: CodeScrollSettings.State, kind: EditorKind?): Boolean = when (kind) {
            EditorKind.MAIN_EDITOR -> s.showMain
            EditorKind.DIFF -> s.showDiff
            EditorKind.PREVIEW -> s.showPreviewEditors
            EditorKind.CONSOLE -> s.showConsole
            EditorKind.UNTYPED -> s.showUntyped
            else -> false
        }
    }
}

package dev.codescroll

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable

/**
 * Reports what the minimap can see in an editor: the IDE mode, the highlighter and how many of its tokens get a
 * color, and the editor markup by minimap category with its renderer class names. Used to tune the plugin for
 * JetBrains Client (Remote Development), where highlighting and markup are mirrored from the backend.
 */
object Diagnostics {
    private val log = logger<Diagnostics>()

    /**
     * Set `-Dcodescroll.diagnostics=true` or the environment variable `CODESCROLL_DIAGNOSTICS=true` to log a report
     * for every editor that gets a minimap. The variable also reaches a split-mode frontend, which the backend starts
     * through the native launcher without the backend's system properties.
     */
    val automatic: Boolean
        get() = System.getProperty("codescroll.diagnostics") == "true" || System.getenv("CODESCROLL_DIAGNOSTICS") == "true"

    // Gutter icon tooltips may be built from PSI, which needs a read action.
    fun report(editor: Editor): String =
        ApplicationManager.getApplication().runReadAction(Computable { buildReport(editor) })

    private fun buildReport(editor: Editor): String = buildString {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document)
        appendLine("Code Scroll diagnostics")
        // The product code tells a JetBrains Client (Remote Development frontend) from a regular IDE.
        val app = ApplicationInfo.getInstance()
        appendLine("product: ${app.fullApplicationName}, build ${app.build.asString()}")
        appendLine("editor: ${editor.javaClass.name}, kind: ${editor.editorKind}, file: ${file?.name} (${file?.fileType?.name})")
        appendLine("document: ${document.lineCount} lines, ${document.textLength} chars")
        val highlighter = (editor as? EditorEx)?.highlighter
        appendLine("highlighter: ${highlighter?.javaClass?.name}")
        if (highlighter != null) {
            val colors = MapRaster.TokenColors(editor.colorsScheme, editor.project, file)
            var tokens = 0
            var typed = 0
            var ownColor = 0
            var editorColor = 0
            val iterator = highlighter.createIterator(0)
            while (!iterator.atEnd() && tokens < 2000) {
                tokens++
                val type = iterator.tokenType
                if (type != null) typed++
                if (colors.color(type) != -1) ownColor++
                if (iterator.textAttributes?.foregroundColor != null) editorColor++
                iterator.advance()
            }
            appendLine("tokens (first $tokens): typed $typed, colored by our highlighters $ownColor, colored by the editor $editorColor")
        }
        markup("editor markup", editor.markupModel, editor)
        markup("document markup", DocumentMarkupModel.forDocument(document, editor.project, false), editor)
    }

    private fun StringBuilder.markup(title: String, model: MarkupModel?, editor: Editor) {
        val extended = model as? MarkupModelEx
        if (extended == null) {
            appendLine("$title: none")
            return
        }
        val kinds = sortedMapOf<String, Int>()
        val renderers = sortedSetOf<String>()
        val layers = sortedMapOf<Int, Int>()
        val striped = sortedMapOf<String, Int>()
        val icons = sortedSetOf<String>()
        extended.processRangeHighlightersOverlappingWith(0, editor.document.textLength) { highlighter ->
            val stripe = highlighter.getErrorStripeMarkColor(editor.colorsScheme)
            val kind = MarkupClassifier.classify(
                highlighter.textAttributesKey?.externalName,
                (highlighter as? RangeHighlighterEx)?.isThinErrorStripeMark == true,
                highlighter.lineMarkerRenderer?.javaClass?.name,
                highlighter.gutterIconRenderer?.javaClass?.name,
                stripe != null,
                highlighter.gutterIconRenderer?.let { "${it.tooltipText} ${it.accessibleName}" },
            )
            kinds.merge(kind.name, 1, Int::plus)
            if (stripe != null) striped.merge(kind.name, 1, Int::plus)
            highlighter.gutterIconRenderer?.let { renderer ->
                if (icons.size < 8) icons += "${renderer.tooltipText}|${renderer.accessibleName}|${renderer.icon}"
            }
            layers.merge(highlighter.layer, 1, Int::plus)
            highlighter.lineMarkerRenderer?.let { if (renderers.size < 12) renderers += "line:" + it.javaClass.name }
            highlighter.gutterIconRenderer?.let { if (renderers.size < 12) renderers += "gutter:" + it.javaClass.name }
            true
        }
        appendLine("$title: kinds $kinds, with stripe color $striped, layers $layers")
        if (icons.isNotEmpty()) appendLine("$title gutter icons (tooltip|accessible name|icon): $icons")
        if (renderers.isNotEmpty()) appendLine("$title renderers: $renderers")
    }

    fun log(editor: Editor) = log.info(report(editor))
}

/** Find Action: "Code Scroll: Dump Diagnostics". Logs the report and shows it. */
class DumpDiagnosticsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val report = Diagnostics.report(editor)
        Diagnostics.log(editor)
        Messages.showInfoMessage(editor.project, report, "Code Scroll Diagnostics")
    }
}

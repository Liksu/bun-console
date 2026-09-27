package dev.jsconsole.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager

/** Light input documents need full invalidation when an edit changes a surrounding expression. */
class ConsoleInputHighlighting(project: Project, document: Document, parent: Disposable) : Disposable {
    private var disposed = false
    private var revision = 0L

    init {
        Disposer.register(parent, this)
        val documents = PsiDocumentManager.getInstance(project)
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val currentRevision = ++revision
                // Defer until PSI reflects the edit, and discard superseded callbacks.
                // The daemon's own scheduler debounces the subsequent analysis.
                documents.performLaterWhenAllCommitted {
                    if (!disposed && !project.isDisposed && revision == currentRevision) {
                        documents.getPsiFile(document)?.takeIf { it.isValid }?.let { file ->
                            DaemonCodeAnalyzer.getInstance(project).restart(file, "JS Console input changed")
                        }
                    }
                }
            }
        }, this)
    }

    override fun dispose() { disposed = true }
}

package dev.jsconsole.ui

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Key

/** Only console input editors opt in; all other editors keep their original handlers. */
abstract class ConsoleHistoryHandler(
    private val original: EditorActionHandler,
    private val previous: Boolean,
) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val navigate = editor.getUserData(NAVIGATE)
        val line = editor.caretModel.visualPosition.line
        val lastLine = editor.offsetToVisualPosition(editor.document.textLength).line
        if (navigate != null && editor.caretModel.caretCount == 1 &&
            !editor.selectionModel.hasSelection() && LookupManager.getActiveLookup(editor) == null &&
            (if (previous) line == 0 else line == lastLine)) {
            navigate(previous)
        } else {
            original.execute(editor, caret, dataContext)
        }
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        editor.getUserData(NAVIGATE) != null || original.isEnabled(editor, caret, dataContext)

    override fun executeInCommand(editor: Editor, dataContext: DataContext?): Boolean =
        original.executeInCommand(editor, dataContext)

    class Up(original: EditorActionHandler) : ConsoleHistoryHandler(original, true)
    class Down(original: EditorActionHandler) : ConsoleHistoryHandler(original, false)

    companion object {
        val NAVIGATE: Key<(Boolean) -> Unit> = Key.create("dev.jsconsole.history.navigate")
    }
}

package dev.jsconsole.ui

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.util.Key
import dev.jsconsole.settings.JsConsoleSettings

/** Remaps Enter only for the console input; completion and other editors keep IDE behavior. */
class ConsoleEnterHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val run = editor.getUserData(RUN)
        if (run != null && JsConsoleSettings.getInstance().enterRuns &&
            editor.getUserData(FORCE_NEWLINE) != true && LookupManager.getActiveLookup(editor) == null) {
            run()
        } else {
            original.execute(editor, caret, dataContext)
        }
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        editor.getUserData(RUN) != null || original.isEnabled(editor, caret, dataContext)

    override fun executeInCommand(editor: Editor, dataContext: DataContext?): Boolean =
        original.executeInCommand(editor, dataContext)

    companion object {
        val RUN: Key<() -> Unit> = Key.create("dev.jsconsole.enter.run")
        val FORCE_NEWLINE: Key<Boolean> = Key.create("dev.jsconsole.enter.newline")
    }
}

/** Shift+Enter uses the ordinary Enter editor handler, preserving indentation. */
class ConsoleShiftEnterHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (editor.getUserData(ConsoleEnterHandler.RUN) == null || !JsConsoleSettings.getInstance().enterRuns) {
            original.execute(editor, caret, dataContext)
            return
        }
        editor.putUserData(ConsoleEnterHandler.FORCE_NEWLINE, true)
        try {
            EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
                .execute(editor, caret, dataContext)
        } finally {
            editor.putUserData(ConsoleEnterHandler.FORCE_NEWLINE, null)
        }
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        editor.getUserData(ConsoleEnterHandler.RUN) != null || original.isEnabled(editor, caret, dataContext)

    override fun executeInCommand(editor: Editor, dataContext: DataContext?): Boolean =
        original.executeInCommand(editor, dataContext)
}

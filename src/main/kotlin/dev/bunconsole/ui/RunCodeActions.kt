package dev.bunconsole.ui

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.bunconsole.runtime.ConsoleFiles
import dev.bunconsole.service.BunConsoleProjectService

/**
 * The editor whose code the run actions use: the one the action comes from, or, from the
 * console's title bar, the selected editor (never the console's own input or output).
 */
private fun sourceEditor(event: AnActionEvent): Editor? {
    val project = event.project ?: return null
    return event.getData(CommonDataKeys.EDITOR)?.takeIf { it.editorKind == EditorKind.MAIN_EDITOR }
        ?: FileEditorManager.getInstance(project).selectedTextEditor
}

private fun Editor.file(): VirtualFile? = FileDocumentManager.getInstance().getFile(document)

/** Shows the console without taking focus from the editor, then runs [action] there. */
private fun inConsole(project: Project, action: (BunConsoleProjectService) -> Unit) {
    ToolWindowManager.getInstance(project).getToolWindow("Bun Console")?.show {
        action(project.getService(BunConsoleProjectService::class.java))
    }
}

/** Context menus hide an action that does not apply; the title bar keeps its buttons in place. */
private fun AnActionEvent.applies(enabled: Boolean) {
    if (isFromContextMenu) presentation.isEnabledAndVisible = enabled else presentation.isEnabled = enabled
}

/** Runs the whole file again, as a program: its top-level code, then prints its module. */
class RunFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun file(event: AnActionEvent): VirtualFile? =
        event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf(ConsoleFiles::isSource)
            ?: sourceEditor(event)?.file()?.takeIf(ConsoleFiles::isSource)

    override fun update(event: AnActionEvent) {
        val file = file(event)
        event.applies(event.project != null && file != null)
        if (event.place == ActionPlaces.TOOLWINDOW_TITLE) {
            event.presentation.text = file?.let { "Run ${it.name}" } ?: "Run File (open a JavaScript or TypeScript file)"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = file(event) ?: return
        inConsole(project) { it.runFile(file) }
    }
}

/** Runs the code selected in the editor as console input; TypeScript and JSX are compiled first. */
class RunSelectionAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun selection(event: AnActionEvent): Pair<String, VirtualFile?>? {
        val editor = sourceEditor(event) ?: return null
        val text = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: return null
        return text to editor.file()
    }

    override fun update(event: AnActionEvent) {
        val selected = selection(event) != null
        event.applies(event.project != null && selected)
        if (event.place == ActionPlaces.TOOLWINDOW_TITLE) {
            event.presentation.text = if (selected) "Run Selection" else "Run Selection (select code in the editor)"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val (code, file) = selection(event) ?: return
        inConsole(project) { it.execute(code, loader = ConsoleFiles.loader(file)) }
    }
}

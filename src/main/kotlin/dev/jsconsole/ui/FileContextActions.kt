package dev.jsconsole.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.jsconsole.runtime.ConsoleFiles
import dev.jsconsole.service.JsConsoleProjectService

private fun sourceFile(event: AnActionEvent): VirtualFile? =
    event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf(ConsoleFiles::isSource)

class AddFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = sourceFile(event)
        event.presentation.isEnabledAndVisible = project != null && file != null &&
            project.serviceIfCreated<JsConsoleProjectService>()?.isFileAdded(file) != true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = sourceFile(event) ?: return
        ToolWindowManager.getInstance(project).getToolWindow("JS Console")?.show {
            project.getService(JsConsoleProjectService::class.java).addFile(file)
        }
    }
}

class RemoveFileAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = sourceFile(event)
        event.presentation.isEnabledAndVisible = project != null && file != null &&
            project.serviceIfCreated<JsConsoleProjectService>()?.isFileAdded(file) == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = sourceFile(event) ?: return
        project.getService(JsConsoleProjectService::class.java).removeFile(file)
    }
}

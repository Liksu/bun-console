package dev.jsconsole.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.icons.AllIcons
import dev.jsconsole.service.JsConsoleProjectService

class JsConsoleToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(JsConsoleProjectService::class.java)
        val tabs = ConsoleContextTabs(toolWindow, service)
        val restart = object : DumbAwareAction("Restart Runtime", "Reload all modules in a fresh Bun runtime", AllIcons.Actions.Restart) {
            override fun actionPerformed(event: AnActionEvent) { service.restart() }
        }
        val panel = JsConsolePanel(project) {
            tabs.refresh()
            if (service.restartRequired) toolWindow.setTitleActions(listOf(restart))
            else toolWindow.setTitleActions(emptyList())
        }
        toolWindow.setAdditionalGearActions(panel.menuActions)
        tabs.install(panel)
    }
}

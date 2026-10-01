package dev.bunconsole.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.icons.AllIcons
import dev.bunconsole.service.BunConsoleProjectService

class BunConsoleToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(BunConsoleProjectService::class.java)
        val tabs = ConsoleContextTabs(toolWindow, service)
        val restart = object : DumbAwareAction("Restart Runtime", "Reload all modules in a fresh Bun runtime", AllIcons.Actions.Restart) {
            override fun actionPerformed(event: AnActionEvent) { service.restart() }
        }
        val debugger = DebuggerToggleAction(project, service)
        val panel = BunConsolePanel(project) {
            tabs.refresh()
            toolWindow.setTitleActions(if (service.restartRequired || service.blocked) listOf(debugger, restart) else listOf(debugger))
        }
        Disposer.register(toolWindow.contentManager, panel)
        // While the tool window is hidden, the console neither starts nor follows the editor.
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = service.setConsoleVisible(toolWindow.isVisible)
        })
        service.setConsoleVisible(toolWindow.isVisible)
        toolWindow.setAdditionalGearActions(panel.menuActions)
        tabs.install(panel)
    }
}

package dev.bunconsole.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
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
        val runActions = listOf("dev.bunconsole.RunFile", "dev.bunconsole.RunSelection").mapNotNull(ActionManager.getInstance()::getAction)
        // Title bar: [debugger] [restart, when needed] | [run file] [run selection] | [scroll to the end] [clear] — then the ⋮ menu.
        var outputActions = emptyList<AnAction>()
        fun updateTitleActions() {
            val runtime = if (service.restartRequired || service.blocked) listOf(debugger, restart) else listOf(debugger)
            toolWindow.setTitleActions(runtime + Separator.getInstance() + runActions + Separator.getInstance() + outputActions)
        }
        val panel = BunConsolePanel(project) {
            tabs.refresh()
            updateTitleActions()
        }
        outputActions = panel.outputActions
        updateTitleActions()
        // The run buttons are used while the editor has the focus; keep them visible then too.
        toolWindow.component.putClientProperty(ToolWindowContentUi.DONT_HIDE_TOOLBAR_IN_HEADER, true)
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

package dev.jsconsole.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import dev.jsconsole.service.JsConsoleProjectService
import java.awt.BorderLayout
import javax.swing.JPanel

/** Native tool-window tabs show the context files; all tabs share one console view and runtime. */
internal class ConsoleContextTabs(
    private val toolWindow: ToolWindow,
    private val service: JsConsoleProjectService,
) {
    private val manager = toolWindow.contentManager
    private val contents = linkedMapOf<String, Content>()
    private var panel: JsConsolePanel? = null

    private val listener = object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) { showSharedPanel() }
    }

    fun install(console: JsConsolePanel) {
        panel = console
        manager.addContentManagerListener(listener)
        Disposer.register(console, Disposable { manager.removeContentManagerListener(listener) })
        refresh()
    }

    fun refresh() {
        val console = panel ?: return
        if (toolWindow.isDisposed) return
        val tabs = service.contextTabs()
        val keys = tabs.mapTo(hashSetOf()) { it.key }
        val priorSelection = manager.selectedContent

        for (tab in tabs) {
            val content = contents[tab.key] ?: ContentFactory.getInstance()
                .createContent(JPanel(BorderLayout()), tab.label, false).also {
                    it.isCloseable = false
                    if (tab.key == "main") it.setDisposer(console)
                    contents[tab.key] = it
                    manager.addContent(it)
                }
            content.displayName = tab.label
            content.description = if (tab.key == "main") "${tab.path ?: "JavaScript"} · ${service.status}" else tab.path
        }

        val main = contents.getValue("main")
        val desiredSelection = priorSelection?.takeIf { selected ->
            contents.any { (key, value) -> key in keys && value == selected }
        } ?: main
        for ((key, content) in contents.toMap()) {
            if (key in keys) continue
            if (manager.selectedContent == content) manager.setSelectedContent(main)
            contents.remove(key)
            manager.removeContent(content, true)
        }
        if (manager.selectedContent != desiredSelection) manager.setSelectedContent(desiredSelection)
        showSharedPanel()
    }

    private fun showSharedPanel() {
        val console = panel ?: return
        val target = (manager.selectedContent ?: contents["main"])?.component as? JPanel ?: return
        if (console.parent == target) return
        console.parent?.remove(console)
        target.add(console, BorderLayout.CENTER)
        target.revalidate()
        target.repaint()
    }
}

package dev.bunconsole.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import dev.bunconsole.service.BunConsoleProjectService
import java.awt.BorderLayout
import javax.swing.JPanel

/** Native tool-window tabs show the context files; all tabs share one console view and runtime. */
internal class ConsoleContextTabs(
    private val toolWindow: ToolWindow,
    private val service: BunConsoleProjectService,
) {
    private val manager = toolWindow.contentManager
    private val contents = linkedMapOf<String, Content>()
    private var panel: BunConsolePanel? = null
    private var updating = false

    private val listener = object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) {
            if (updating) return
            showSharedPanel()
            val selected = manager.selectedContent ?: return
            val key = contents.entries.firstOrNull { it.value == selected }?.key ?: return
            val tab = service.contextTabs().firstOrNull { it.key == key } ?: return
            if (!tab.active && tab.path != null) service.openContextFile(tab.path)
        }
    }

    fun install(console: BunConsolePanel) {
        panel = console
        manager.addContentManagerListener(listener)
        Disposer.register(console, Disposable { manager.removeContentManagerListener(listener) })
        refresh()
    }

    fun refresh() {
        if (panel == null || toolWindow.isDisposed) return
        val tabs = service.contextTabs()
        val keys = tabs.mapTo(hashSetOf()) { it.key }
        updating = true
        try {
            for ((key, content) in contents.toMap()) {
                if (key in keys) continue
                contents.remove(key)
                manager.removeContent(content, true)
            }
            for ((index, tab) in tabs.withIndex()) {
                val content = contents[tab.key] ?: ContentFactory.getInstance()
                    .createContent(JPanel(BorderLayout()), tab.label, false).also {
                        it.isCloseable = false
                        contents[tab.key] = it
                    }
                content.displayName = tab.label
                content.description = tab.path ?: "JavaScript"
                if (manager.contents.getOrNull(index) != content) {
                    if (content in manager.contents) manager.removeContent(content, false)
                    manager.addContent(content, index)
                }
            }
            val active = tabs.firstOrNull { it.active }?.let { contents[it.key] }
            if (active != null && manager.selectedContent != active) manager.setSelectedContent(active)
        } finally { updating = false }
        showSharedPanel()
    }

    private fun showSharedPanel() {
        val console = panel ?: return
        val target = (manager.selectedContent ?: contents.values.firstOrNull())?.component as? JPanel ?: return
        if (console.parent == target) return
        console.parent?.remove(console)
        target.add(console, BorderLayout.CENTER)
        target.revalidate()
        target.repaint()
    }
}

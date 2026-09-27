package dev.jsconsole.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.LanguageTextField
import com.intellij.ui.JBSplitter
import com.intellij.psi.PsiDocumentManager
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.settings.JsConsoleConfigurable
import dev.jsconsole.settings.JsConsoleSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class JsConsolePanel(private val project: Project, statusChanged: (String) -> Unit = {}) : JPanel(BorderLayout()), Disposable {
    private val service = project.getService(JsConsoleProjectService::class.java)
    private val output = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val fileType = FileTypeManager.getInstance().getFileTypeByExtension("js")
    private val input = LanguageTextField((fileType as LanguageFileType).language, project, "", false)
    private val syntaxPrinter = ConsoleSyntaxPrinter(project, fileType)
    private var historyIndex = 0
    private var draft = ""
    private var disposed = false
    private var runShortcutRegistered = false

    private fun command(text: String, description: String, enabled: () -> Boolean = { true }, perform: () -> Unit) =
        object : DumbAwareAction(text, description, null) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = !disposed && !project.isDisposed && enabled()
            }
            override fun actionPerformed(event: AnActionEvent) {
                if (!disposed && !project.isDisposed && enabled()) perform()
            }
        }

    private val runInput = command("Run Input", "Execute the console input", { input.text.isNotBlank() }) { execute() }
    private val noShortcut = command("", "") {}
    val menuActions = DefaultActionGroup().apply {
        add(runInput)
        addSeparator()
        add(command("Restart Runtime", "Start a fresh Bun runtime; reset console variables") { service.restart() })
        add(command("Clear Output", "Clear output; keep variables, input and command history") { service.clearOutput() })
        add(object : ToggleAction("Pin File Context", "Keep the current file context when switching editors", null), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                super.update(event)
                event.presentation.isEnabled = !disposed && !project.isDisposed && service.hasCurrentFile
            }
            override fun isSelected(event: AnActionEvent): Boolean = service.pinned
            override fun setSelected(event: AnActionEvent, selected: Boolean) {
                if (!disposed && !project.isDisposed && selected != service.pinned) service.togglePin()
            }
        })
        addSeparator()
        add(command("JS Console Settings…", "Choose the Bun executable") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JsConsoleConfigurable::class.java)
        })
    }

    init {
        Disposer.register(this, output)
        input.setDisposedWith(this)
        input.setFontInheritedFromLAF(false)
        ConsoleInputHighlighting(project, input.document, this)
        PsiDocumentManager.getInstance(project).getPsiFile(input.document)
            ?.putUserData(ConsoleCompletionContributor.INPUT, true)
        val entry = JPanel(BorderLayout())
        entry.minimumSize = Dimension(0, 44)
        input.preferredSize = Dimension(500, 100)
        entry.add(JLabel("  >  "), BorderLayout.WEST)
        entry.add(input, BorderLayout.CENTER)
        val splitter = JBSplitter(true, 0.75f)
        splitter.setFirstComponent(output.component)
        splitter.setSecondComponent(entry)
        splitter.setSplitterProportionKey("dev.jsconsole.inputHeight")
        add(splitter, BorderLayout.CENTER)
        val settings = JsConsoleSettings.getInstance()
        updateRunShortcut()
        settings.onEnterModeChanged(this) {
            if (SwingUtilities.isEventDispatchThread()) updateRunShortcut()
            else SwingUtilities.invokeLater { if (!disposed) updateRunShortcut() }
        }
        bind("historyPrevious", KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK)) { previous() }
        bind("historyNext", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK)) { next() }
        input.addSettingsProvider { editor ->
            editor.settings.isLineNumbersShown = false
            editor.settings.isUseSoftWraps = true
            editor.setVerticalScrollbarVisible(true)
            editor.putUserData(ConsoleHistoryHandler.NAVIGATE) { previous -> if (previous) previous() else next() }
            editor.putUserData(ConsoleEnterHandler.RUN) { execute() }
            editor.contentComponent.addFocusListener(object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) { service.refreshContextIfNeeded() }
            })
        }
        input.toolTipText = "Enter behavior is set in Settings > Tools > JS Console; Up/Down at the edges browse history"
        service.attach({ text, kind, styles ->
            when (kind) {
                JsConsoleProjectService.OutputKind.JAVASCRIPT -> syntaxPrinter.print(text, styles, output::print)
                JsConsoleProjectService.OutputKind.RESULT -> syntaxPrinter.print(text, append = output::print)
                JsConsoleProjectService.OutputKind.CLEAR -> output.clear()
                JsConsoleProjectService.OutputKind.ERROR -> output.print(text, ConsoleViewContentType.ERROR_OUTPUT)
                JsConsoleProjectService.OutputKind.NORMAL -> output.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
            }
        }, statusChanged)
        historyIndex = service.history.size
    }

    private fun bind(name: String, key: KeyStroke, action: () -> Unit) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name)
        actionMap.put(name, object : AbstractAction() { override fun actionPerformed(event: ActionEvent) { action() } })
    }

    private fun updateRunShortcut() {
        if (runShortcutRegistered) {
            runInput.unregisterCustomShortcutSet(this)
            runShortcutRegistered = false
        }
        if (JsConsoleSettings.getInstance().enterRuns) {
            runInput.copyShortcutFrom(noShortcut)
        } else {
            runInput.copyShortcutFrom(ActionManager.getInstance().getAction("dev.jsconsole.RunInput"))
            runInput.registerCustomShortcutSet(this, this)
            runShortcutRegistered = true
        }
    }

    private fun execute() {
        if (input.text.isBlank()) return
        saveHistoryEdits()
        service.execute(input.text, input.getEditor(false)?.let { syntaxPrinter.capture(it) } ?: emptyList())
        input.text = ""
        draft = ""
        historyIndex = service.history.size
    }
    private fun previous() {
        saveHistoryEdits()
        if (historyIndex > 0) {
            input.text = service.history[--historyIndex]
            input.caretModel.moveToOffset(0)
        }
    }
    private fun next() {
        saveHistoryEdits()
        if (historyIndex < service.history.size) {
            historyIndex++
            input.text = service.history.getOrNull(historyIndex) ?: draft
            input.caretModel.moveToOffset(input.document.textLength)
        }
    }
    private fun saveHistoryEdits() {
        if (historyIndex == service.history.size) draft = input.text
        else if (historyIndex in service.history.indices) service.history[historyIndex] = input.text
    }
    override fun dispose() { disposed = true; service.detach() }
}

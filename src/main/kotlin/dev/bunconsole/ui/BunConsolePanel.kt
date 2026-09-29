package dev.bunconsole.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.LanguageTextField
import com.intellij.ui.JBSplitter
import com.intellij.psi.PsiDocumentManager
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleConfigurable
import dev.bunconsole.settings.BunConsoleSettings
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Color
import java.awt.Font
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager

// DevTools colors: warnings are dark amber on pale yellow (light) or yellow on dark olive (dark).
private val WARNING_COLOR = JBColor(Color(0x8A5A00), Color(0xFFD479))
private val WARNING_OUTPUT = ConsoleViewContentType("Bun Console warning",
    TextAttributes(WARNING_COLOR, JBColor(Color(0xFFFBE5), Color(0x3A3217)), null, null, Font.PLAIN))

class BunConsolePanel(private val project: Project, statusChanged: (String) -> Unit = {}) : JPanel(BorderLayout()), Disposable {
    private val service = project.getService(BunConsoleProjectService::class.java)
    private val output = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val fileType = FileTypeManager.getInstance().getFileTypeByExtension("js")
    private val input = LanguageTextField((fileType as LanguageFileType).language, project, "", false)
    private val syntaxPrinter = ConsoleSyntaxPrinter(project, fileType)
    private var historyIndex = 0
    private var draft = ""
    private var disposed = false
    private var runShortcutRegistered = false
    private val runButton = JButton("Run").apply { isEnabled = false; addActionListener { execute() } }
    private val runHint = JLabel()
    private val statusLine = JBLabel()

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

    private fun debugCommand(text: String, perform: () -> Unit) = object : DumbAwareAction(text) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(event: AnActionEvent) {
            event.presentation.isVisible = !disposed && !project.isDisposed && service.debugPaused
        }
        override fun actionPerformed(event: AnActionEvent) {
            if (service.debugPaused) perform()
        }
    }

    private val runInput = command("Run Input", "Execute the console input", { input.text.isNotBlank() }) { execute() }
    private val noShortcut = command("", "") {}
    val menuActions = DefaultActionGroup().apply {
        add(runInput)
        addSeparator()
        add(command("Restart Runtime", "Start a fresh Bun runtime; reset console variables") { service.restart() })
        add(command("Clear Output", "Clear output; keep variables, input and command history") { service.clearOutput() })
        addSeparator()
        add(DebuggerToggleAction(project, service))
        add(debugCommand("Continue") { service.resume() })
        add(debugCommand("Step Over") { service.stepOver() })
        add(debugCommand("Step Into") { service.stepInto() })
        add(debugCommand("Step Out") { service.stepOut() })
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
        add(command("Bun Console Settings…", "Choose the Bun executable") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, BunConsoleConfigurable::class.java)
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
        statusLine.font = statusLine.font.deriveFont((statusLine.font.size2D - 1f).coerceAtLeast(9f))
        statusLine.border = JBUI.Borders.empty(2, 8)
        statusLine.isVisible = false
        entry.add(statusLine, BorderLayout.NORTH)
        val runControl = JPanel(BorderLayout()).apply {
            add(runButton, BorderLayout.CENTER)
            add(runHint, BorderLayout.SOUTH)
        }
        runHint.horizontalAlignment = JLabel.CENTER
        runHint.font = runHint.font.deriveFont((runHint.font.size2D - 2f).coerceAtLeast(9f))
        runHint.foreground = UIManager.getColor("Label.disabledForeground")
        entry.add(runControl, BorderLayout.EAST)
        input.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                runButton.isEnabled = !disposed && input.text.isNotBlank()
            }
        }, this)
        output.component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateColumns()
        })
        val splitter = JBSplitter(true, 0.75f)
        splitter.setFirstComponent(output.component)
        splitter.setSecondComponent(entry)
        splitter.setSplitterProportionKey("dev.bunconsole.inputHeight")
        add(splitter, BorderLayout.CENTER)
        val settings = BunConsoleSettings.getInstance()
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
        input.toolTipText = "Enter behavior is set in Settings > Tools > Bun Console; Up/Down at the edges browse history"
        service.attach({ text, kind, styles ->
            when (kind) {
                BunConsoleProjectService.OutputKind.JAVASCRIPT -> syntaxPrinter.print(text, styles, output::print)
                BunConsoleProjectService.OutputKind.RESULT -> syntaxPrinter.print(text, append = output::print)
                BunConsoleProjectService.OutputKind.CLEAR -> output.clear()
                BunConsoleProjectService.OutputKind.ERROR -> output.print(text, ConsoleViewContentType.ERROR_OUTPUT)
                BunConsoleProjectService.OutputKind.WARNING -> output.print(text, WARNING_OUTPUT)
                BunConsoleProjectService.OutputKind.NORMAL -> output.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
            }
        }, { status -> showStatus(status); statusChanged(status) })
        historyIndex = service.history.size
    }

    /** Tell the runtime how many characters fit in a line of the output (minus the `[N] ` prefix). */
    private fun updateColumns() {
        val width = output.component.width
        if (width <= 0) return
        val font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.CONSOLE_PLAIN)
        val charWidth = output.component.getFontMetrics(font).charWidth('0').coerceAtLeast(1)
        service.outputColumns = ((width - JBUI.scale(32)) / charWidth - 6).coerceIn(40, 1000)
    }

    /** Text of the status line above the input, or null while it is hidden. */
    internal val statusText: String? get() = statusLine.text.takeIf { statusLine.isVisible }

    /** The context tabs already name the file; the line shows only what is happening (running, busy, reload…). */
    private fun showStatus(status: String) {
        val label = service.contextTabs().firstOrNull { it.active }?.label
        val detail = if (label != null && status.startsWith(label)) status.removePrefix(label).removePrefix(" · ") else status
        statusLine.text = detail
        statusLine.isVisible = detail.isNotBlank()
        statusLine.foreground = if (service.blocked) WARNING_COLOR else UIManager.getColor("Label.disabledForeground")
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
        if (BunConsoleSettings.getInstance().enterRuns) {
            runHint.text = "Enter"
            runInput.copyShortcutFrom(noShortcut)
        } else {
            runHint.text = "Ctrl+Enter"
            runInput.copyShortcutFrom(ActionManager.getInstance().getAction("dev.bunconsole.RunInput"))
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

/** Switches console debugging on or off at once; Bun needs a restart for its inspector. */
class DebuggerToggleAction(private val project: Project, private val service: BunConsoleProjectService) :
    ToggleAction("Debugger", "Breakpoints stop console calls; switching restarts the runtime", AllIcons.Actions.StartDebugger), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun isSelected(event: AnActionEvent): Boolean = BunConsoleSettings.getInstance().debugEnabled
    override fun setSelected(event: AnActionEvent, selected: Boolean) {
        if (!project.isDisposed) service.setDebuggerEnabled(selected)
    }
}

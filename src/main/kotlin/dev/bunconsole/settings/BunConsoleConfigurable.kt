package dev.bunconsole.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import dev.bunconsole.runtime.BunRuntimeLocator
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ButtonGroup

class BunConsoleConfigurable : Configurable {
    private var automatic: JBCheckBox? = null
    private var executable: JBTextField? = null
    private var browse: JButton? = null
    private var debugger: JBCheckBox? = null
    private var enterNewline: JBRadioButton? = null
    private var enterExecutes: JBRadioButton? = null

    override fun getDisplayName(): String = "Bun Console"
    override fun getPreferredFocusedComponent(): JComponent? = automatic

    override fun createComponent(): JComponent {
        val auto = JBCheckBox("Find Bun automatically (PATH, BUN_INSTALL, ~/.bun/bin)")
        val path = JBTextField()
        val picker = JButton("Browse…").apply {
            addActionListener {
                FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile(), null, null)?.let { path.text = it.path }
            }
        }
        automatic = auto
        executable = path
        browse = picker
        val attachDebugger = JBCheckBox("Start console with debugger")
        debugger = attachDebugger
        val newlineOnEnter = JBRadioButton("Enter: new line; Ctrl+Enter: run")
        val runOnEnter = JBRadioButton("Enter: run; Shift+Enter: new line")
        ButtonGroup().apply { add(newlineOnEnter); add(runOnEnter) }
        enterNewline = newlineOnEnter
        enterExecutes = runOnEnter
        auto.addActionListener { updateEnabled() }
        val pathRow = JPanel(BorderLayout(8, 0)).apply { add(path, BorderLayout.CENTER); add(picker, BorderLayout.EAST) }
        val label = JBLabel("Bun executable:").apply { labelFor = path }
        val panel = FormBuilder.createFormBuilder()
            .addComponent(auto)
            .addLabeledComponent(label, pathRow)
            .addComponent(JBLabel("Bun 1.4 or newer is required. This setting applies to all projects on this computer."))
            .addComponent(JBLabel("Changes take effect on the next console Restart. The current session keeps running."))
            .addSeparator()
            .addComponent(attachDebugger)
            .addComponent(JBLabel("Enables breakpoints and paused-frame evaluation. Debug is shown only when execution pauses."))
            .addSeparator()
            .addComponent(JBLabel("Console input keys (take effect immediately):"))
            .addComponent(newlineOnEnter)
            .addComponent(runOnEnter)
            .addComponentFillVertically(JPanel(), 0).panel
        reset()
        return panel
    }

    private fun selectedPath(): String = if (automatic?.isSelected != false) "" else executable?.text.orEmpty().trim()
    private fun updateEnabled() {
        val manual = automatic?.isSelected == false
        executable?.isEnabled = manual
        browse?.isEnabled = manual
    }

    override fun isModified(): Boolean = automatic != null &&
        (selectedPath() != BunConsoleSettings.getInstance().bunPath ||
            debugger?.isSelected != BunConsoleSettings.getInstance().debugEnabled ||
            enterExecutes?.isSelected != BunConsoleSettings.getInstance().enterRuns ||
            (automatic?.isSelected == false && selectedPath().isEmpty()))

    override fun apply() {
        if (automatic == null) return
        val path = selectedPath()
        if (automatic?.isSelected == false) {
            if (path.isEmpty()) throw ConfigurationException("Choose the Bun executable or enable automatic detection.")
            try { BunRuntimeLocator.locate(path) }
            catch (error: IllegalArgumentException) { throw ConfigurationException(error.message ?: "Invalid Bun executable path.") }
        }
        BunConsoleSettings.getInstance().bunPath = path
        BunConsoleSettings.getInstance().debugEnabled = debugger?.isSelected == true
        BunConsoleSettings.getInstance().enterRuns = enterExecutes?.isSelected == true
    }

    override fun reset() {
        val saved = BunConsoleSettings.getInstance().bunPath
        automatic?.isSelected = saved.isEmpty()
        executable?.text = saved
        debugger?.isSelected = BunConsoleSettings.getInstance().debugEnabled
        enterExecutes?.isSelected = BunConsoleSettings.getInstance().enterRuns
        enterNewline?.isSelected = !BunConsoleSettings.getInstance().enterRuns
        updateEnabled()
    }

    override fun disposeUIResources() {
        automatic = null; executable = null; browse = null; debugger = null
        enterNewline = null; enterExecutes = null
    }
}

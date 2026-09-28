package dev.jsconsole

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBSplitter
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.ui.DebuggerToggleAction
import dev.jsconsole.ui.JsConsolePanel
import dev.jsconsole.ui.ConsoleSyntaxPrinter
import dev.jsconsole.settings.JsConsoleSettings
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.KeyStroke

class JsConsolePanelTest : BasePlatformTestCase() {
    fun testInputHasJavaScriptPsiAndExecutesThroughItsAction() {
        val editors = FileEditorManager.getInstance(project)
        editors.openFiles.forEach { editors.closeFile(it) }
        val panel = JsConsolePanel(project)
        val service = project.getService(JsConsoleProjectService::class.java)
        try {
            val menu = panel.menuActions.getChildren(null)
            val runInput = menu.single { it.templatePresentation.text == "Run Input" }
            val actionContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
            fun event(action: com.intellij.openapi.actionSystem.AnAction) = AnActionEvent.createEvent(
                action, actionContext, action.templatePresentation.clone(), "JSConsoleTest", ActionUiKind.NONE, null
            )
            val input = descendants(panel).filterIsInstance<EditorTextField>().single()
            assertTrue(descendants(panel).filterIsInstance<JBSplitter>().single().isVertical)
            assertEquals("ECMAScript 6", PsiDocumentManager.getInstance(project).getPsiFile(input.document)!!.language.id)
            val shortcut = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
            assertTrue(runInput.shortcutSet.shortcuts.filterIsInstance<KeyboardShortcut>().any { it.firstKeyStroke == shortcut })
            val emptyEvent = event(runInput)
            runInput.update(emptyEvent)
            assertFalse(emptyEvent.presentation.isEnabled)
            val output = StringBuilder()
            service.attach({ text, _, _ -> output.append(text) }, {})
            input.text = "21 * 2"
            runInput.actionPerformed(event(runInput))
            PlatformTestUtil.waitWithEventsDispatching("Input did not evaluate", { output.contains("[1] 42") }, 20)
            assertEquals("", input.text)
            assertEquals("21 * 2", service.history.last())

            // Instantiate the real field editor and exercise the IDE's registered arrow actions.
            // A Swing key listener or an unrelated panel action would not pass these checks.
            val editor = input.getEditor(true)!!
            val scheme = EditorColorsManager.getInstance().globalScheme
            assertEquals(scheme.getFont(EditorFontType.PLAIN), editor.colorsScheme.getFont(EditorFontType.PLAIN))
            val scrollPane = editor.scrollPane
            input.text = (1..40).joinToString("\n") { "line $it" }
            panel.setSize(600, 400)
            panel.doLayout()
            input.parent.doLayout()
            input.doLayout()
            editor.component.doLayout()
            scrollPane.setSize(500, 100)
            scrollPane.doLayout()
            editor.caretModel.moveToOffset(0)
            editor.scrollingModel.scrollVertically(0)
            val initialScroll = editor.scrollingModel.verticalScrollOffset
            scrollPane.dispatchEvent(MouseWheelEvent(scrollPane, MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 40, 40, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 3))
            assertTrue("Wheel did not scroll multiline console input: policy=${scrollPane.verticalScrollBarPolicy}, " +
                "wheel=${scrollPane.isWheelScrollingEnabled}, pane=${scrollPane.size}, " +
                "viewport=${scrollPane.viewport.size}, view=${scrollPane.viewport.view.size}, " +
                "bar=${scrollPane.verticalScrollBar.value}/${scrollPane.verticalScrollBar.maximum}/" +
                "${scrollPane.verticalScrollBar.visibleAmount}, offset=${editor.scrollingModel.verticalScrollOffset}",
                editor.scrollingModel.verticalScrollOffset > initialScroll)
            input.text = ""
            val context = SimpleDataContext.builder().add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.PROJECT, project).build()
            fun arrow(up: Boolean) {
                EditorActionManager.getInstance().getActionHandler(
                    if (up) IdeActions.ACTION_EDITOR_MOVE_CARET_UP else IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN
                ).execute(editor, null, context)
            }
            arrow(true)
            assertEquals("21 * 2", input.text)
            arrow(false)
            assertEquals("", input.text)

            val multiline = "const d = new Date()\nd.toISOString().split(/\\D/)"
            input.text = multiline
            runInput.actionPerformed(event(runInput))
            input.text = "unfinished draft"
            arrow(true)
            assertEquals(multiline, input.text)
            arrow(true)
            assertEquals("21 * 2", input.text)
            arrow(true)
            assertEquals("21 * 2", input.text)
            arrow(false)
            assertEquals(multiline, input.text)
            arrow(false)
            assertEquals("unfinished draft", input.text)
            arrow(false)
            assertEquals("unfinished draft", input.text)

            // Edits belong to their history entry, including when leaving in either direction.
            arrow(true)
            val editedMultiline = "$multiline.filter(Boolean)"
            input.text = editedMultiline
            editor.caretModel.moveToOffset(0)
            arrow(true)
            input.text = "6 * 7"
            arrow(false)
            assertEquals(editedMultiline, input.text)
            arrow(false)
            assertEquals("unfinished draft", input.text)
            arrow(true)
            assertEquals(editedMultiline, input.text)
            arrow(true)
            assertEquals("6 * 7", input.text)
            assertEquals(listOf("6 * 7", editedMultiline), service.history)
            arrow(false)
            arrow(false)

            input.text = "first\nsecond\nthird"
            editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 2))
            arrow(true)
            assertEquals("first\nsecond\nthird", input.text)
            assertEquals(0, editor.caretModel.logicalPosition.line)
            arrow(false)
            assertEquals(1, editor.caretModel.logicalPosition.line)

            // Arrow keys must still navigate completion when its popup is open.
            input.text = ""
            LookupManager.getInstance(project).showLookup(editor,
                LookupElementBuilder.create("alpha"), LookupElementBuilder.create("beta"))
            try {
                arrow(true)
                arrow(false)
                assertEquals("", input.text)
            } finally {
                LookupManager.getInstance(project).hideActiveLookup()
            }

            // Input syntax remains active after clearing and restoring a history entry.
            input.text = "const value = 'hello'"
            val iterator = editor.highlighter.createIterator(0)
            val keywordAttributes = iterator.textAttributes
            while (!iterator.atEnd() && iterator.start < input.text.indexOf("'")) iterator.advance()
            assertFalse(iterator.atEnd())
            assertFalse(keywordAttributes == iterator.textAttributes)

            // A normal project editor still moves the caret, without console history.
            myFixture.configureByText("ordinary.js", "first\nsecond")
            myFixture.editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 0))
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_MOVE_CARET_UP)
            assertEquals("first\nsecond", myFixture.editor.document.text)
            assertEquals(0, myFixture.editor.caretModel.logicalPosition.line)

            val fragments = mutableListOf<Pair<String, ConsoleViewContentType>>()
            ConsoleSyntaxPrinter(project, FileTypeManager.getInstance().getFileTypeByExtension("js"))
                .print(multiline) { text, type -> fragments.add(text to type) }
            assertEquals(multiline, fragments.joinToString("") { it.first })
            assertTrue(fragments.first { it.first == "const" }.second != ConsoleViewContentType.NORMAL_OUTPUT)
            assertTrue(fragments.map { it.second }.distinct().size > 2)

            val replay = mutableListOf<Pair<String, JsConsoleProjectService.OutputKind>>()
            service.attach({ text, kind, _ -> replay.add(text to kind) }, {})
            assertTrue(replay.contains(multiline to JsConsoleProjectService.OutputKind.JAVASCRIPT))
            val historyBeforeClear = service.history.toList()
            val inputBeforeClear = input.text
            val clear = menu.single { it.templatePresentation.text == "Clear Output" }
            clear.actionPerformed(event(clear))
            assertEquals(JsConsoleProjectService.OutputKind.CLEAR, replay.last().second)
            assertEquals(historyBeforeClear, service.history)
            assertEquals(inputBeforeClear, input.text)
            val afterClear = StringBuilder()
            service.attach({ text, _, _ -> afterClear.append(text) }, {})
            assertEquals("", afterClear.toString())
            service.execute("d instanceof Date")
            PlatformTestUtil.waitWithEventsDispatching("Clear reset the runtime", { afterClear.contains("[3] true") }, 20)
            val pin = menu.filterIsInstance<ToggleAction>().single { it.templatePresentation.text == "Pin File Context" }
            assertTrue(menu.any { it is DebuggerToggleAction })
            assertFalse(pin.isSelected(event(pin)))
            val pinEvent = event(pin)
            pin.update(pinEvent)
            assertEquals(service.hasCurrentFile, pinEvent.presentation.isEnabled)
            val restart = menu.single { it.templatePresentation.text == "Restart Runtime" }
            restart.actionPerformed(event(restart))
            service.execute("typeof d")
            PlatformTestUtil.waitWithEventsDispatching("Menu restart did not reset variables", { afterClear.contains("[4] 'undefined'") }, 20)

            val settings = JsConsoleSettings.getInstance()
            val savedSettings = settings.state
            try {
                settings.enterRuns = false
                input.text = "first"
                editor.caretModel.moveToOffset(input.text.length)
                WriteCommandAction.runWriteCommandAction(project) {
                    EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
                        .execute(editor, null, context)
                }
                assertEquals("first\n", input.text)

                settings.enterRuns = true
                assertFalse(runInput.shortcutSet.shortcuts.filterIsInstance<KeyboardShortcut>()
                    .any { it.firstKeyStroke == shortcut })
                input.text = "6 * 7"
                WriteCommandAction.runWriteCommandAction(project) {
                    EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
                        .execute(editor, null, context)
                }
                PlatformTestUtil.waitWithEventsDispatching("Enter did not execute", { afterClear.contains("[5] 42") }, 20)
                assertEquals("", input.text)

                input.text = "leftRight"
                editor.caretModel.moveToOffset(4)
                WriteCommandAction.runWriteCommandAction(project) {
                    EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_START_NEW_LINE)
                        .execute(editor, null, context)
                }
                assertEquals("left\nRight", input.text)
                assertEquals("6 * 7", service.history.last())

                LookupManager.getInstance(project).showLookup(editor,
                    LookupElementBuilder.create("left"), LookupElementBuilder.create("right"))
                try {
                    val historyCount = service.history.size
                    WriteCommandAction.runWriteCommandAction(project) {
                        EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
                            .execute(editor, null, context)
                    }
                    assertEquals("Enter must not execute while completion is open", historyCount, service.history.size)
                } finally {
                    LookupManager.getInstance(project).hideActiveLookup()
                }
            } finally {
                settings.loadState(savedSettings)
                if (!savedSettings.enterRuns) {
                    assertTrue(runInput.shortcutSet.shortcuts.filterIsInstance<KeyboardShortcut>()
                        .any { it.firstKeyStroke == shortcut })
                }
            }
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    private fun descendants(component: Container): Sequence<java.awt.Component> = sequence {
        for (child in component.components) {
            yield(child)
            if (child is Container) yieldAll(descendants(child))
        }
    }
}

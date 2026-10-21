package dev.bunconsole

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import dev.bunconsole.runtime.ConsoleFiles
import dev.bunconsole.service.BunConsoleProjectService
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Run File and Run Selection, from the console's title bar (no editor in the data context). */
class RunCodeActionsTest : ConsoleTestCase() {
    fun testRunTheWholeFileAgainAndRunTheSelectedTypeScript() {
        val directory = Files.createTempDirectory("bun-console-run")
        val source = "globalThis.mainRuns = (globalThis.mainRuns ?? 0) + 1;\n" +
            "console.log('main runs', globalThis.mainRuns);\n" +
            "function twice(value: number): number { return value * 2 }\n" +
            "export const runs: number = globalThis.mainRuns;\n" +
            "const sample: number = twice(21);\n" +
            "twice(sample as number)\n"
        val path = Files.writeString(directory.resolve("main.ts"), source)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)!!
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        fun updated(id: String): AnActionEvent {
            val action: AnAction = ActionManager.getInstance().getAction(id)
            val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(),
                ActionPlaces.TOOLWINDOW_TITLE, ActionUiKind.TOOLBAR, null)
            action.update(event)
            return event
        }
        fun waitFor(what: String, condition: () -> Boolean) = try {
            PlatformTestUtil.waitWithEventsDispatching(what, condition, 20)
        } catch (error: AssertionError) { throw AssertionError("${error.message}; status=${service.status}; output=$output", error) }
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            val runFile = updated("dev.bunconsole.RunFile")
            assertTrue(runFile.presentation.isEnabledAndVisible)
            assertEquals("Run main.ts", runFile.presentation.text)
            val noSelection = updated("dev.bunconsole.RunSelection")
            assertTrue(noSelection.presentation.isVisible)
            assertFalse(noSelection.presentation.isEnabled)

            service.runFile(file)
            waitFor("File did not run") { output.contains("[1] Module {") }
            assertTrue(output.toString(), output.contains("[1] ▶ main.ts"))
            assertTrue(output.toString(), output.contains("main runs 1"))
            assertTrue(output.toString(), output.contains("runs: 1") && output.contains("sample: 42"))
            service.runFile(file)
            waitFor("File did not run again") { output.contains("[2] Module {") }
            assertTrue(output.toString(), output.contains("main runs 2") && output.contains("runs: 2"))

            val start = source.indexOf("twice(sample")
            editor.selectionModel.setSelection(start, source.indexOf('\n', start))
            assertTrue(updated("dev.bunconsole.RunSelection").presentation.isEnabled)
            service.execute(editor.selectionModel.selectedText!!, loader = ConsoleFiles.loader(file))
            waitFor("Selection did not run") { output.contains("[3] 84") }
            assertEquals("twice(sample as number)", service.history.last())
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            FileEditorManager.getInstance(project).closeFile(file)
            directory.toFile().deleteRecursively()
        }
    }
}

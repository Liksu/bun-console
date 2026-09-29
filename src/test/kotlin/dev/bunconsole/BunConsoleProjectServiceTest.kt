package dev.bunconsole

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.xdebugger.XDebuggerManager
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleSettings

class BunConsoleProjectServiceTest : BasePlatformTestCase() {
    fun testCurrentFilePinRestartAndPersistentHistory() {
        val directory = Files.createTempDirectory("bun-console-project-fixture")
        val a = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(directory.resolve("a.ts"), "export function twice(x: number) { return x; }"))!!
        val b = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(directory.resolve("b.ts"), "export const other = 9;"))!!
        FileEditorManager.getInstance(project).openFile(a, true)
        val settings = BunConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = false
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
        // A tool window may be restored from an unsafe startup callback, outside a write-safe transaction.
        val aDocument = FileDocumentManager.getInstance().getDocument(a)!!
        WriteCommandAction.runWriteCommandAction(project) {
            aDocument.setText("export function twice(x: number) { return x * 2; }")
        }
        ApplicationManager.getApplication().invokeLater({
            service.attach({ text, _, _ -> output.append(text) }, {})
            service.execute("twice(21)") // Still queues before the deferred save/import.
        }, ModalityState.any())
        awaitOutput(output, "[1] 42")
        assertTrue(XDebuggerManager.getInstance(project).debugSessions.none { it.sessionName == "Bun Console" })
        service.execute("const mine = 7")
        awaitOutput(output, "[2] undefined")
        service.togglePin()
        awaitOutput(output, "Added file a.ts")
        FileEditorManager.getInstance(project).openFile(b, true)
        service.execute("[twice(mine), other].join(',')")
        awaitOutput(output, "[3] '14,9'")
        service.removeFile(a)
        awaitOutput(output, "Removed file a.ts")
        service.execute("[typeof twice, other, mine].join(',')")
        awaitOutput(output, "[4] 'undefined,9,7'")
        service.restart()
        service.execute("[typeof mine, other].join(',')")
        awaitOutput(output, "[5] 'undefined,9'")
        assertEquals(5, service.history.size)
        assertTrue(output.contains("[1] 42"))
        val bDocument = FileDocumentManager.getInstance().getDocument(b)!!
        WriteCommandAction.runWriteCommandAction(project) { bDocument.setText("export const other = 11;") }
        ApplicationManager.getApplication().invokeLater({
            service.restart()
            service.execute("other")
        }, ModalityState.any())
        awaitOutput(output, "[6] 11")
        assertEquals("export const other = 11;", Files.readString(directory.resolve("b.ts")))
        service.execute("import path from 'path'\npath.join(process.cwd(), 'texts').endsWith('texts')")
        awaitOutput(output, "[7] true")
        service.execute("path.basename('/a/b')")
        awaitOutput(output, "[8] 'b'")
        service.execute("import {twice as double} from './a';\nimport * as paths from 'node:path';\n[double(21), paths.basename('/x/y')].join(',')")
        awaitOutput(output, "[9] '42,y'")
        service.execute("import {other} from './b'; other")
        awaitOutput(output, "[10] 11")
        service.execute("const first = [1]\nimport 'node:path'\n[2, 3].map(x => x * first[0]).join(',')")
        awaitOutput(output, "[11] '2,3'")
        service.addFile(a)
        PlatformTestUtil.waitWithEventsDispatching("File was not re-added", { service.isFileAdded(a) }, 25)
        assertTrue(service.isFileAdded(a))
        assertEquals(listOf("a.ts · pinned", "b.ts · follows editor"), service.contextTabs().map { it.label })
        assertTrue(service.contextNames.contains("twice"))
        service.restart()
        service.execute("twice(6)")
        awaitOutput(output, "[12] 12")
        assertTrue(service.contextNames.contains("twice"))
        service.removeFile(a)
        PlatformTestUtil.waitWithEventsDispatching("File was not removed", { !service.isFileAdded(a) }, 25)
        assertFalse(service.isFileAdded(a))
        assertEquals(listOf("b.ts · follows editor"), service.contextTabs().map { it.label })
        service.execute("typeof twice")
        awaitOutput(output, "[13] 'undefined'")
        service.addSymbol(a, "twice")
        awaitOutput(output, "Added a.ts: twice as twice")
        service.restart()
        service.execute("twice(7)")
        awaitOutput(output, "[14] 14")
        service.execute("const repeated = 1; repeated")
        awaitOutput(output, "[15] 1")
        service.execute("const repeated = 2; repeated")
        awaitOutput(output, "[16] 2")
        service.execute("repeated")
        awaitOutput(output, "[17] 2")
        WriteCommandAction.runWriteCommandAction(project) {
            bDocument.setText("export const other = 11; export const arr = [1, 2];")
        }
        service.restart()
        service.execute("const arr = [3, 4]")
        awaitOutput(output, "[18] undefined")
        service.execute("const arr = 42")
        awaitOutput(output, "[19] undefined")
        service.execute("arr")
        awaitOutput(output, "[20] 42")
        service.execute("globalThis['b.ts'].arr.join(',')")
        awaitOutput(output, "[21] '1,2'")
        assertFalse(output.contains("Redeclared names were local"))
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.loadState(originalSettings)
            FileEditorManager.getInstance(project).closeFile(a)
            FileEditorManager.getInstance(project).closeFile(b)
            Files.deleteIfExists(directory.resolve("a.ts"))
            Files.deleteIfExists(directory.resolve("b.ts"))
            Files.deleteIfExists(directory)
        }
    }

    private fun awaitOutput(output: StringBuilder, expected: String) {
        try { PlatformTestUtil.waitWithEventsDispatching("Missing $expected", { output.contains(expected) }, 25) }
        catch (error: AssertionError) { throw AssertionError("Missing $expected; actual output: $output", error) }
    }
}

package dev.bunconsole

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.ui.ConsoleCompletionContributor
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ConsoleStaleCompletionTest : BasePlatformTestCase() {
    fun testChangedFileRefreshesWithoutResetAndDependencyRequestsRestart() {
        val directory = Files.createTempDirectory("bun-console-stale")
        val dependencyPath = Files.writeString(directory.resolve("dependency.ts"), "export const dependent = 'old';")
        val extraPath = Files.writeString(directory.resolve("extra.ts"), "export const extra = 'old';")
        val path = Files.writeString(directory.resolve("b.ts"),
            "import { dependent } from './dependency'; export const b = dependent;")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val dependencyFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependencyPath)!!
        val extraFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(extraPath)!!
        FileEditorManager.getInstance(project).openFile(file, true)
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            service.activate() // The runtime starts on first use, not when the tool window opens.
            PlatformTestUtil.waitWithEventsDispatching("Initial export not loaded", { "b" in service.contextNames }, 20)
            service.execute("const mine = 7")
            PlatformTestUtil.waitWithEventsDispatching("Console variable not initialized", { output.contains("[1] undefined") }, 20)
            // Context files run on first use; this one is now loaded, so the runtime keeps its old exports.
            service.execute("b")
            PlatformTestUtil.waitWithEventsDispatching("Context file not used", { output.contains("[2] 'old'") }, 20)
            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().getDocument(file)!!
                    .setText("import { dependent } from './dependency'; export const b = dependent; export const initialText = 'new';")
            }
            PlatformTestUtil.waitWithEventsDispatching("Stale context was not reported", {
                service.status.contains("updates when console is focused")
            }, 20)
            service.togglePin()
            // Pinning is asynchronous; the editor switch below must not race it.
            PlatformTestUtil.waitWithEventsDispatching("Pin not applied", { service.pinned }, 20)
            assertTrue(service.status.contains("updates when console is focused"))
            myFixture.addFileToProject("lib/b.ts", "export const initialText = 'new';")
            val consoleFile = myFixture.configureByText("console.js", "ini<caret>")
            consoleFile.putUserData(ConsoleCompletionContributor.INPUT, true)
            val items = myFixture.completeBasic().orEmpty()
            assertFalse("Console must not offer an import for an export absent from the runtime; " +
                "names=${service.contextNames}; status=${service.status}; output=$output",
                items.any { it.lookupString == "initialText" })
            assertTrue("Ordinary JavaScript completion should remain available",
                items.any { it.lookupString == "isFinite" })
            LookupManager.getInstance(project).hideActiveLookup()

            service.refreshContextIfNeeded()
            try {
                PlatformTestUtil.waitWithEventsDispatching("Changed export not reloaded", {
                    "initialText" in service.contextNames
                }, 20)
            } catch (error: AssertionError) {
                throw AssertionError("${error.message}; status=${service.status}; names=${service.contextNames}; " +
                    "saved=${Files.readString(path)}; output=$output", error)
            }
            assertFalse(service.status.contains("Restart Runtime"))
            val freshInput = myFixture.configureByText("fresh-console.js", "ini<caret>")
            freshInput.putUserData(ConsoleCompletionContributor.INPUT, true)
            val freshItems = myFixture.completeBasic().orEmpty()
            val candidate = freshItems.single { it.lookupString == "initialText" }
            myFixture.lookup.currentItem = candidate
            myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals("initialText", myFixture.editor.document.text)
            service.execute(myFixture.editor.document.text)
            PlatformTestUtil.waitWithEventsDispatching("Fresh export did not evaluate", {
                output.contains("[3] 'new'")
            }, 20)
            service.execute("mine")
            PlatformTestUtil.waitWithEventsDispatching("Reload lost console variables", {
                output.contains("[4] 7")
            }, 20)

            service.addFile(extraFile)
            PlatformTestUtil.waitWithEventsDispatching("Added file not loaded", { "extra" in service.contextNames }, 20)
            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().getDocument(extraFile)!!
                    .setText("export const extra = 'new'; export const another = 9;")
            }
            service.refreshContextIfNeeded()
            try {
                PlatformTestUtil.waitWithEventsDispatching("Added file not refreshed", { "another" in service.contextNames }, 20)
            } catch (error: AssertionError) {
                throw AssertionError("${error.message}; status=${service.status}; names=${service.contextNames}; " +
                    "saved=${Files.readString(extraPath)}; output=$output", error)
            }
            service.execute("[extra, another, mine].join(',')")
            try {
                PlatformTestUtil.waitWithEventsDispatching("Added file reload lost state", {
                    output.contains("[5] 'new,9,7'")
                }, 20)
            } catch (error: AssertionError) {
                throw AssertionError("${error.message}; status=${service.status}; output=$output", error)
            }

            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().getDocument(dependencyFile)!!.setText("export const dependent = 'new';")
            }
            service.refreshContextIfNeeded()
            PlatformTestUtil.waitWithEventsDispatching("Changed dependency did not request Restart", {
                service.status.contains("Restart Runtime to update dependencies")
            }, 20)
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            FileEditorManager.getInstance(project).closeFile(file)
            Files.deleteIfExists(path)
            Files.deleteIfExists(extraPath)
            Files.deleteIfExists(dependencyPath)
            Files.deleteIfExists(directory)
        }
    }
}

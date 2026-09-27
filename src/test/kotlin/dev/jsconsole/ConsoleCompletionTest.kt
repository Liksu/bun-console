package dev.jsconsole

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.ui.ConsoleCompletionContributor
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ConsoleCompletionTest : BasePlatformTestCase() {
    fun testTabCompletesRuntimeExportWithoutImportAndEvaluatesIt() {
        val directory = Files.createTempDirectory("js-console-completion")
        val path = directory.resolve("a.ts")
        val addedPath = directory.resolve("b.ts")
        Files.writeString(path, "export const lexical = 'available'; export const lexicalOther = 2;")
        Files.writeString(addedPath, "export const initialText = 'from b';")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val addedFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(addedPath)!!
        FileEditorManager.getInstance(project).openFile(file, true)
        val service = project.getService(JsConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            PlatformTestUtil.waitWithEventsDispatching("Exports not loaded", { "lexical" in service.contextNames }, 20)
            service.togglePin()
            val consoleFile = myFixture.configureByText("console.js", "lex<caret>")
            consoleFile.putUserData(ConsoleCompletionContributor.INPUT, true)
            val items = myFixture.completeBasic() ?: error("Expected multiple completion choices")
            val lexical = items.filter { it.lookupString == "lexical" }
            assertEquals("Auto-import duplicate must be removed", 1, lexical.size)
            myFixture.lookup.currentItem = lexical.single()
            myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR) // Tab, not a synthetic text replacement.
            assertEquals("lexical", myFixture.editor.document.text)
            service.execute(myFixture.editor.document.text)
            PlatformTestUtil.waitWithEventsDispatching("Completed command failed", { output.contains("[1] 'available'") }, 20)

            val ordinary = myFixture.configureByText("ordinary.js", "lex<caret>")
            assertNull(ordinary.getUserData(ConsoleCompletionContributor.INPUT))
            val ordinaryItems = myFixture.completeBasic()
            assertFalse(ordinaryItems.orEmpty().any { item ->
                val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
                item.renderElement(presentation)
                presentation.typeText == "JS Console"
            })

            service.addFile(addedFile)
            PlatformTestUtil.waitWithEventsDispatching("Added context not loaded", { "initialText" in service.contextNames }, 20)
            val addedConsoleFile = myFixture.configureByText("added-console.js", "ini<caret>")
            addedConsoleFile.putUserData(ConsoleCompletionContributor.INPUT, true)
            val addedItems = myFixture.completeBasic() ?: error("Expected added-file completion choices")
            val matching = addedItems.filter { it.lookupString == "initialText" }
            assertEquals("Auto-import duplicate from added file must be removed", 1, matching.size)
            myFixture.lookup.currentItem = matching.single()
            myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals("initialText", myFixture.editor.document.text)
            service.execute(myFixture.editor.document.text)
            PlatformTestUtil.waitWithEventsDispatching("Added export did not evaluate", { output.contains("[2] 'from b'") }, 20)
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            FileEditorManager.getInstance(project).closeFile(file)
            Files.deleteIfExists(path)
            Files.deleteIfExists(addedPath)
            Files.deleteIfExists(directory)
        }
    }
}

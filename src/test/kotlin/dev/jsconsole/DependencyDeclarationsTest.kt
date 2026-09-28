package dev.jsconsole

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.settings.JsConsoleSettings
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Manual check 6: a file first loaded as another module's dependency still exposes its top-level names. */
class DependencyDeclarationsTest : BasePlatformTestCase() {
    fun testImportedFileKeepsUnexportedNamesWhenOpenedLater() {
        val directory = Files.createTempDirectory("js-console-dependency-names")
        Files.writeString(directory.resolve("store.ts"), "const items: string[] = [];\nexport function add(item: string) { return items.push(item); }")
        Files.writeString(directory.resolve("uses.ts"), "import { add } from './store';\nexport function useStore() { add('apple'); return add('pear'); }")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
        val uses = root.findChild("uses.ts")!!
        val store = root.findChild("store.ts")!!
        PsiTestUtil.addContentRoot(module, root)
        val settings = JsConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = false
        FileEditorManager.getInstance(project).openFile(uses, true)
        val service = project.getService(JsConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            PlatformTestUtil.waitWithEventsDispatching("Context not ready: $output", { "useStore" in service.contextNames }, 30)
            service.execute("useStore()")
            PlatformTestUtil.waitWithEventsDispatching("Dependency did not run: $output", { output.contains("[1] 2") }, 20)
            FileEditorManager.getInstance(project).openFile(store, true)
            PlatformTestUtil.waitWithEventsDispatching("Store context not ready: $output", { "add" in service.contextNames }, 20)
            service.execute("items.join()")
            PlatformTestUtil.waitWithEventsDispatching("Unexported name of the dependency unavailable: $output", {
                output.contains("[2] 'apple,pear'")
            }, 20)
            assertFalse(output.toString(), output.contains("Unavailable until Restart Runtime"))
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.loadState(originalSettings)
            FileEditorManager.getInstance(project).closeFile(uses)
            FileEditorManager.getInstance(project).closeFile(store)
            PsiTestUtil.removeContentEntry(module, root)
            directory.toFile().deleteRecursively()
        }
    }
}

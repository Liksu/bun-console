package dev.bunconsole

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleSettings
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Nothing runs until the user works with the console; a hidden console does not follow the editor. */
class BunConsoleIdleTest : BasePlatformTestCase() {
    fun testRuntimeStartsOnFirstUseAndHiddenConsoleDoesNotFollowTheEditor() {
        val directory = Files.createTempDirectory("bun-console-idle")
        val first = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(directory.resolve("first.ts"), "export const one = 1;"))!!
        val second = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(directory.resolve("second.ts"), "export const two = 2;"))!!
        val settings = BunConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = false
        val editors = FileEditorManager.getInstance(project)
        editors.openFile(first, true)
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        fun activeTab() = service.contextTabs().single { it.active }.label
        fun settle(seconds: Long) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            PlatformTestUtil.waitWithEventsDispatching("settle", { System.nanoTime() >= deadline }, seconds.toInt() + 1)
        }
        try {
            // Restored tool window: nothing starts, the tab still names the editor file.
            service.attach({ text, _, _ -> output.append(text) }, {})
            settle(2)
            assertEquals("Bun starts when you use the console", service.status)
            assertFalse(output.toString(), output.contains("Starting fresh runtime"))
            assertTrue(service.contextNames.isEmpty())
            assertEquals("first.ts · follows editor", activeTab())
            editors.openFile(second, true)
            PlatformTestUtil.waitWithEventsDispatching("Tab did not follow", { activeTab() == "second.ts · follows editor" }, 5)
            assertFalse(output.contains("Starting fresh runtime"))

            // First command starts the runtime with the editor's file as context.
            service.execute("two + 40")
            PlatformTestUtil.waitWithEventsDispatching("First command did not run: $output", { output.contains("[1] 42") }, 30)

            // Hidden console: switching files loads nothing; showing it catches up once.
            service.setConsoleVisible(false)
            editors.openFile(first, true)
            settle(2)
            assertFalse("Hidden console followed the editor: $output", output.contains("Loaded first.ts"))
            service.setConsoleVisible(true)
            PlatformTestUtil.waitWithEventsDispatching("Shown console did not catch up: $output", { output.contains("Loaded first.ts") }, 20)
            service.execute("one")
            PlatformTestUtil.waitWithEventsDispatching("Context after catch-up: $output", { output.contains("[2] 1") }, 20)
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.loadState(originalSettings)
            editors.closeFile(first)
            editors.closeFile(second)
            directory.toFile().deleteRecursively()
        }
    }
}

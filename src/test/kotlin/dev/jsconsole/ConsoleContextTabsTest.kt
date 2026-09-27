package dev.jsconsole

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.ui.JsConsoleToolWindowFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

class ConsoleContextTabsTest : BasePlatformTestCase() {
    fun testEachFileHasNativeTabButConsolePanelIsShared() {
        val directory = Files.createTempDirectory("js-console-tabs")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
            Files.writeString(directory.resolve("a.ts"), "export const a = 1")
        )!!
        val secondFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
            Files.writeString(directory.resolve("b.ts"), "export const b = 2")
        )!!
        val nestedDirectory = Files.createDirectory(directory.resolve("nested"))
        val sameName = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
            Files.writeString(nestedDirectory.resolve("a.ts"), "export const c = 3")
        )!!
        val service = project.getService(JsConsoleProjectService::class.java)
        val toolWindow = ToolWindowManager.getInstance(project).registerToolWindow("JS Console Tabs Test") {
            anchor = ToolWindowAnchor.BOTTOM
            canCloseContent = false
        }
        try {
            JsConsoleToolWindowFactory().createToolWindowContent(project, toolWindow)
            val manager = toolWindow.contentManager
            assertEquals(1, manager.contents.size)
            val mainContainer = manager.contents.single().component as JPanel
            val console = mainContainer.components.single()
            service.addFile(file)
            PlatformTestUtil.waitWithEventsDispatching("Missing file tab", { manager.contents.size == 2 }, 25)
            assertEquals(listOf("JavaScript · follows editor", "a.ts · pinned"), manager.contents.map { it.displayName })
            service.addFile(secondFile)
            PlatformTestUtil.waitWithEventsDispatching("Missing second file tab", { manager.contents.size == 3 }, 25)
            assertEquals(listOf("JavaScript · follows editor", "a.ts · pinned", "b.ts · pinned"), manager.contents.map { it.displayName })
            manager.setSelectedContent(manager.contents[2])
            assertSame(console, (manager.contents[2].component as JPanel).components.single())
            service.removeFile(file)
            PlatformTestUtil.waitWithEventsDispatching("File tab was not removed", { manager.contents.size == 2 }, 25)
            assertEquals(listOf("JavaScript · follows editor", "b.ts · pinned"), manager.contents.map { it.displayName })
            assertSame(console, (manager.contents[1].component as JPanel).components.single())
            service.removeFile(secondFile)
            PlatformTestUtil.waitWithEventsDispatching("Second tab was not removed", { manager.contents.size == 1 }, 25)
            assertSame(console, (manager.contents.single().component as JPanel).components.single())
            service.addFile(file)
            service.addFile(sameName)
            PlatformTestUtil.waitWithEventsDispatching("Matching file tabs were not added", { manager.contents.size == 3 }, 25)
            assertEquals(listOf("JavaScript · follows editor", "${directory.fileName}/a.ts · pinned", "nested/a.ts · pinned"),
                manager.contents.map { it.displayName })
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            toolWindow.contentManager.removeAllContents(true)
            Files.deleteIfExists(directory.resolve("a.ts"))
            Files.deleteIfExists(directory.resolve("b.ts"))
            Files.deleteIfExists(nestedDirectory.resolve("a.ts"))
            Files.deleteIfExists(nestedDirectory)
            Files.deleteIfExists(directory)
        }
    }
}

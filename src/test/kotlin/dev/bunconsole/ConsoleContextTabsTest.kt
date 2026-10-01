package dev.bunconsole

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.ui.BunConsoleToolWindowFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

class ConsoleContextTabsTest : BasePlatformTestCase() {
    fun testEachFileHasNativeTabButConsolePanelIsShared() {
        val directory = Files.createTempDirectory("bun-console-tabs")
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
        val service = project.getService(BunConsoleProjectService::class.java)
        val toolWindow = ToolWindowManager.getInstance(project).registerToolWindow("Bun Console Tabs Test") {
            anchor = ToolWindowAnchor.BOTTOM
            canCloseContent = false
        }
        var console: dev.bunconsole.ui.BunConsolePanel? = null
        try {
            BunConsoleToolWindowFactory().createToolWindowContent(project, toolWindow)
            // The test tool window is never shown on screen; a hidden console would not follow the editor.
            service.setConsoleVisible(true)
            val manager = toolWindow.contentManager
            assertEquals(1, manager.contents.size)
            val mainContainer = manager.contents.single().component as JPanel
            console = mainContainer.components.single() as dev.bunconsole.ui.BunConsolePanel
            service.addFile(file)
            PlatformTestUtil.waitWithEventsDispatching("Missing file tab", { manager.contents.size == 2 }, 25)
            assertEquals(listOf("a.ts · pinned", "JavaScript · follows editor"), manager.contents.map { it.displayName })
            service.addFile(secondFile)
            PlatformTestUtil.waitWithEventsDispatching("Missing second file tab", { manager.contents.size == 3 }, 25)
            assertEquals(listOf("a.ts · pinned", "b.ts · pinned", "JavaScript · follows editor"), manager.contents.map { it.displayName })
            manager.setSelectedContent(manager.contents[2])
            assertSame(console, (manager.contents[2].component as JPanel).components.single())
            service.removeFile(file)
            PlatformTestUtil.waitWithEventsDispatching("File tab was not removed", { manager.contents.size == 2 }, 25)
            assertEquals(listOf("b.ts · pinned", "JavaScript · follows editor"), manager.contents.map { it.displayName })
            assertSame(console, (manager.contents[1].component as JPanel).components.single())
            service.removeFile(secondFile)
            PlatformTestUtil.waitWithEventsDispatching("Second tab was not removed", { manager.contents.size == 1 }, 25)
            assertSame(console, (manager.contents.single().component as JPanel).components.single())
            service.addFile(file)
            service.addFile(sameName)
            PlatformTestUtil.waitWithEventsDispatching("Matching file tabs were not added", { manager.contents.size == 3 }, 25)
            assertEquals(listOf("${directory.fileName}/a.ts · pinned", "nested/a.ts · pinned", "JavaScript · follows editor"),
                manager.contents.map { it.displayName })
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
            PlatformTestUtil.waitWithEventsDispatching("Pinned file not selected", { manager.selectedContent == manager.contents[0] }, 25)
            assertEquals(2, manager.contents.size)
            assertEquals(listOf("${directory.fileName}/a.ts · pinned", "nested/a.ts · pinned"), manager.contents.map { it.displayName })
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(secondFile, true)
            PlatformTestUtil.waitWithEventsDispatching("Following file not appended", { manager.contents.size == 3 && manager.selectedContent == manager.contents[2] }, 25)
            assertEquals("b.ts · follows editor", manager.contents[2].displayName)
            manager.setSelectedContent(manager.contents[0])
            PlatformTestUtil.waitWithEventsDispatching("Pinned tab did not open its file", {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull() == file &&
                    manager.contents.size == 2 && manager.selectedContent == manager.contents[0]
            }, 25)
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            console?.let { com.intellij.openapi.util.Disposer.dispose(it) }
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(file)
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(secondFile)
            toolWindow.contentManager.removeAllContents(true)
            Files.deleteIfExists(directory.resolve("a.ts"))
            Files.deleteIfExists(directory.resolve("b.ts"))
            Files.deleteIfExists(nestedDirectory.resolve("a.ts"))
            Files.deleteIfExists(nestedDirectory)
            Files.deleteIfExists(directory)
        }
    }
}

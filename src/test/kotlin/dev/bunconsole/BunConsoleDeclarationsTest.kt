package dev.bunconsole

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleSettings
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BunConsoleDeclarationsTest : BasePlatformTestCase() {
    fun testUnexportedTopLevelDeclarationsOfTheEditorFileAreCallable() {
        val directory = Files.createTempDirectory("bun-console-declarations")
        val path = Files.writeString(directory.resolve("tools.ts"), """
            type Label = string;
            declare const ambient: number;
            const prefix: Label = "#";
            function tag(value: string): Label { return prefix + value; }
            export function shout(value: string) { return tag(value).toUpperCase(); }
        """.trimIndent())
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val settings = BunConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = false
        FileEditorManager.getInstance(project).openFile(file, true)
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            PlatformTestUtil.waitWithEventsDispatching("Context not ready: $output", { "tag" in service.contextNames }, 30)
            assertFalse("ambient" in service.contextNames)
            service.execute("[tag('a'), shout('b'), prefix].join(' ')")
            PlatformTestUtil.waitWithEventsDispatching("Declarations not callable: $output", { output.contains("[1] '#a #B #'") }, 25)
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.loadState(originalSettings)
            FileEditorManager.getInstance(project).closeFile(file)
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}

package dev.bunconsole

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import dev.bunconsole.service.BunConsoleProjectService
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Run Selection never runs a context file by itself to provide one of its names; it asks. */
class RunSelectionQuestionTest : ConsoleTestCase() {
    fun testSelectionAsksBeforeRunningTheFileItNeeds() {
        val directory = Files.createTempDirectory("bun-console-not-run")
        val path = Files.writeString(directory.resolve("scratch.js"),
            "var fns = [], a = 0;\nfor (var i = 0; i < 10; ++i) a++;\nconsole.log('file ran');\n")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        FileEditorManager.getInstance(project).openFile(file, true)
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { notifications += notification }
        })
        fun waitFor(what: String, condition: () -> Boolean) = try {
            PlatformTestUtil.waitWithEventsDispatching(what, condition, 20)
        } catch (error: AssertionError) { throw AssertionError("${error.message}; status=${service.status}; output=$output", error) }
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            service.execute("console.log(a + 12)", fromEditor = true)
            waitFor("Selection was not refused") { notifications.isNotEmpty() }
            assertTrue(output.toString(), output.contains("[1] ReferenceError: a is from scratch.js, which has not run yet"))
            assertFalse("The selection must not run the file by itself: $output", output.contains("file ran"))
            val notification = notifications.single()
            assertEquals("a is from scratch.js, which has not run yet", notification.title)
            val runFirst = notification.actions.single()
            assertEquals("Run scratch.js, then the selection", runFirst.templatePresentation.text)
            Notification.fire(notification, runFirst, null)
            waitFor("File and selection did not run") { output.contains("[3] undefined") }
            assertTrue(output.toString(), output.contains("[2] ▶ scratch.js") && output.contains("file ran") && output.contains("22"))
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            FileEditorManager.getInstance(project).closeFile(file)
            directory.toFile().deleteRecursively()
        }
    }
}

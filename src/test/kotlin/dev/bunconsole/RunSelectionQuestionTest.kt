package dev.bunconsole

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import dev.bunconsole.service.BunConsoleProjectService
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Run Selection never runs a context file by itself to provide one of its names. It reads the
 * file's declarations (its skeleton) and asks to run the file only for values that need it.
 */
class RunSelectionQuestionTest : ConsoleTestCase() {
    fun testSelectionReadsDeclarationsAndAsksForTheRest() {
        val directory = Files.createTempDirectory("bun-console-not-run")
        val path = Files.writeString(directory.resolve("scratch.js"),
            "var fns = [], a = 0;\nconst db = connect();\nfor (var i = 0; i < 10; ++i) a++;\nconsole.log('file ran');\n" +
                "function connect() { return { name: 'db' }; }\n")
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
            // `a` is declared with a value the file states itself: no need to run the file.
            service.execute("console.log(a + 12)", fromEditor = true, file = file)
            waitFor("Selection did not run") { output.contains("[1] undefined") }
            assertTrue(output.toString(), output.contains("\n12\n"))
            // `db` gets its value from project code: the console asks.
            service.execute("db.name", fromEditor = true, file = file)
            waitFor("Selection was not refused") { notifications.isNotEmpty() }
            assertTrue(output.toString(), output.contains("[2] ReferenceError: db is from scratch.js, which has not run yet"))
            assertFalse("The selection must not run the file by itself: $output", output.contains("file ran"))
            val notification = notifications.single()
            assertEquals("db is from scratch.js, which has not run yet", notification.title)
            val runFirst = notification.actions.single()
            assertEquals("Run scratch.js, then the selection", runFirst.templatePresentation.text)
            Notification.fire(notification, runFirst, null)
            waitFor("File and selection did not run") { output.contains("[4] 'db'") }
            assertTrue(output.toString(), output.contains("[3] ▶ scratch.js") && output.contains("file ran"))
            // After the run, the selection reads the run's values.
            service.execute("a", fromEditor = true, file = file)
            waitFor("Selection did not read the run") { output.contains("[5] 10") }
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            FileEditorManager.getInstance(project).closeFile(file)
            directory.toFile().deleteRecursively()
        }
    }
}

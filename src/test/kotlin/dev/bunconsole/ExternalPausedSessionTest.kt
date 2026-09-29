package dev.bunconsole

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import dev.bunconsole.debug.BunDebugBridge
import dev.bunconsole.runtime.BunConsoleProcess
import dev.bunconsole.runtime.BunRuntimeLocator
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleSettings
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A program the user debugs (not the console's own runtime) pauses; console input evaluates in its frame. */
class ExternalPausedSessionTest : BasePlatformTestCase() {
    fun testInputEvaluatesInThePausedFrameOfAnotherJavaScriptSession() {
        val directory = Files.createTempDirectory("bun-console-external-pause")
        val module = Files.writeString(directory.resolve("app.ts"), """
            export function handle(request: string) {
                const reply = request.toUpperCase();
                return reply;
            }
        """.trimIndent())
        val bootstrap = directory.resolve("bootstrap.mjs")
        javaClass.getResourceAsStream("/runtime/bootstrap.mjs")!!.use { Files.copy(it, bootstrap) }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module)!!
        @Suppress("UNCHECKED_CAST")
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.filterIsInstance<XLineBreakpointType<*>>()
            .first { it.id == "javascript" } as XLineBreakpointType<XBreakpointProperties<*>>
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager
        val breakpoint = WriteAction.compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<XBreakpointProperties<*>>, RuntimeException> {
            breakpoints.addLineBreakpoint(type, file.url, 2, type.createBreakpointProperties(file, 2))
        }
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create("C:/Program Files/nodejs/node.exe"))
        val settings = BunConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = false
        val appOutput = StringBuffer()
        var app: BunConsoleProcess? = null
        val appDebugger = BunDebugBridge(project, {}, { app?.close() })
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            app = BunConsoleProcess(BunRuntimeLocator.locate(null), bootstrap, directory,
                { text, _ -> appOutput.append(text) }, { appOutput.append("\nSTOPPED: $it\n") }, appDebugger::attach)
            val started = app.start()
            PlatformTestUtil.waitWithEventsDispatching("App did not start: $appOutput", { started.isDone }, 55)
            started.get()
            val loaded = app.request("load", mapOf("path" to module.toString()))
            PlatformTestUtil.waitWithEventsDispatching("App did not load: $appOutput", { loaded.isDone }, 20)
            val call = app.request("eval", mapOf("code" to "handle('abc')"))
            PlatformTestUtil.waitWithEventsDispatching("App breakpoint not hit: $appOutput", { appDebugger.paused }, 30)

            service.attach({ text, _, _ -> output.append(text) }, {})
            PlatformTestUtil.waitWithEventsDispatching("Pause not shown: ${service.status}", { "paused in" in service.status }, 20)
            assertTrue(service.debugPaused)
            assertTrue("Frame names not offered: ${service.pausedFrameNames()}",
                com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
                    service.pausedFrameNames().containsAll(listOf("request", "reply", "handle"))
                })
            service.execute("request + '!'")
            PlatformTestUtil.waitWithEventsDispatching("Not evaluated in the app frame: $output", {
                output.contains("[1] 'abc!'") || output.contains("[1] \"abc!\"") || output.contains("[1] abc!")
            }, 20)
            service.resume()
            PlatformTestUtil.waitWithEventsDispatching("App call did not finish: $appOutput", { call.isDone }, 20)
            assertEquals("'ABC'", call.get().get("text").asString)
            PlatformTestUtil.waitWithEventsDispatching("Pause label stayed: ${service.status}", { "paused in" !in service.status }, 10)
            service.execute("6 * 7")
            PlatformTestUtil.waitWithEventsDispatching("Console runtime not used after resume: $output", { output.contains("[2] 42") }, 30)
        } finally {
            WriteAction.run<RuntimeException> { breakpoints.removeBreakpoint(breakpoint) }
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.loadState(originalSettings)
            app?.close()
            appDebugger.close()
            app?.termination?.get(5, TimeUnit.SECONDS)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }
}

package dev.bunconsole

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleSettings
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BunDebuggerServiceTest : BasePlatformTestCase() {
    fun testConsoleRoutesPausedInputRestartsAndSurvivesDebuggerStop() {
        val directory = Files.createTempDirectory("bun-console-debug-service")
        val module = Files.writeString(directory.resolve("service-target.ts"), """
            export function twice(x: number) {
                const y = x * 2;
                return y;
            }
        """.trimIndent())
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module)!!
        @Suppress("UNCHECKED_CAST")
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.filterIsInstance<XLineBreakpointType<*>>()
            .first { it.id == "javascript" } as XLineBreakpointType<XBreakpointProperties<*>>
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val breakpoint = WriteAction.compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<XBreakpointProperties<*>>, RuntimeException> {
            manager.addLineBreakpoint(type, file.url, 2, type.createBreakpointProperties(file, 2))
        }
        var breakpointPresent = true
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create("C:/Program Files/nodejs/node.exe"))
        FileEditorManager.getInstance(project).openFile(file, true)
        val settings = BunConsoleSettings.getInstance()
        val originalDebugEnabled = settings.debugEnabled
        settings.debugEnabled = true
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuffer()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            service.activate() // The runtime starts on first use, not when the tool window opens.
            PlatformTestUtil.waitWithEventsDispatching("Context not ready: $output", { "twice" in service.contextNames }, 30)
            service.execute("twice(21)")
            PlatformTestUtil.waitWithEventsDispatching("Breakpoint not hit: $output", { service.debugPaused }, 30)
            PlatformTestUtil.waitWithEventsDispatching("Paused status not shown: ${service.status}", { service.status.contains("paused at") }, 10)
            assertFalse("Pause location belongs in the status line", service.contextTabs().first().label.contains("paused"))
            assertTrue("Frame locals not offered: ${service.pausedFrameNames()}",
                com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
                    service.pausedFrameNames().containsAll(listOf("x", "y", "twice"))
                })
            service.execute("x + y")
            PlatformTestUtil.waitWithEventsDispatching("Paused input did not use frame: $output", { output.contains("[2] 63") }, 20)
            WriteAction.run<RuntimeException> { manager.removeBreakpoint(breakpoint) }
            breakpointPresent = false
            service.restart()
            PlatformTestUtil.waitWithEventsDispatching("Restart did not reload context: $output", { "twice" in service.contextNames }, 30)
            service.execute("twice(3)")
            PlatformTestUtil.waitWithEventsDispatching("New runtime did not evaluate: $output", { output.contains("[3] 6") }, 20)
            XDebuggerManager.getInstance(project).debugSessions.single { it.sessionName == "Bun Console" }.stop()
            PlatformTestUtil.waitWithEventsDispatching("Debugger session did not close", {
                XDebuggerManager.getInstance(project).debugSessions.none { it.sessionName == "Bun Console" }
            }, 10)
            PlatformTestUtil.waitWithEventsDispatching("Debugger disconnect was not reported: $output", {
                output.contains("Debugger disconnected; console continues without breakpoints. Restart Runtime to reconnect")
            }, 10)
            service.execute("twice(4)")
            PlatformTestUtil.waitWithEventsDispatching("Console stopped with debugger: $output", {
                output.contains("[4] 8")
            }, 20)
        } finally {
            if (breakpointPresent) WriteAction.run<RuntimeException> { manager.removeBreakpoint(breakpoint) }
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.debugEnabled = originalDebugEnabled
            PlatformTestUtil.waitWithEventsDispatching("Debugger session did not stop", {
                XDebuggerManager.getInstance(project).debugSessions.none { it.sessionName == "Bun Console" }
            }, 10)
            FileEditorManager.getInstance(project).closeFile(file)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Files.deleteIfExists(module)
            Files.deleteIfExists(directory)
        }
    }

}


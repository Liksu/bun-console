package dev.bunconsole

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleSettings
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BunLateBreakpointTest : BasePlatformTestCase() {
    fun testBreakpointAddedAfterConsoleReady() {
        val directory = Files.createTempDirectory("bun-console-late-breakpoint")
        val module = Files.writeString(directory.resolve("b.ts"), """
            export function test(foo: string): string {
                if (!foo) return '';
                return foo.split('').reverse().join('');
            }
        """.trimIndent())
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module)!!
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create("C:/Program Files/nodejs/node.exe"))
        FileEditorManager.getInstance(project).openFile(file, true)
        val settings = BunConsoleSettings.getInstance()
        val originalDebugEnabled = settings.debugEnabled
        settings.debugEnabled = true
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuffer()
        @Suppress("UNCHECKED_CAST")
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.filterIsInstance<XLineBreakpointType<*>>()
            .first { it.id == "javascript" } as XLineBreakpointType<XBreakpointProperties<*>>
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        var breakpoint: com.intellij.xdebugger.breakpoints.XLineBreakpoint<XBreakpointProperties<*>>? = null
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            PlatformTestUtil.waitWithEventsDispatching("Context not ready: $output", { "test" in service.contextNames }, 30)
            breakpoint = WriteAction.compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<XBreakpointProperties<*>>, RuntimeException> {
                manager.addLineBreakpoint(type, file.url, 2, type.createBreakpointProperties(file, 2))
            }
            service.execute("test('abc')")
            PlatformTestUtil.waitWithEventsDispatching("Late breakpoint not hit: $output", { service.debugPaused || output.contains("[1] 'cba'") }, 10)
            val sessions = XDebuggerManager.getInstance(project).debugSessions.joinToString { "suspended=${it.isSuspended}, position=${it.currentPosition}" }
            assertTrue("Break did not pause; sessions=$sessions; output=$output", service.debugPaused)
            service.resume()
            PlatformTestUtil.waitWithEventsDispatching("Call did not finish: $output", { output.contains("[1] 'cba'") }, 20)
        } finally {
            breakpoint?.let { WriteAction.run<RuntimeException> { manager.removeBreakpoint(it) } }
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


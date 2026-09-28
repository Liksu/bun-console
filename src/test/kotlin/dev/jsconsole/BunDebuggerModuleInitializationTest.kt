package dev.jsconsole

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.debug.PausedFrameEvaluator
import dev.jsconsole.settings.JsConsoleSettings
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BunDebuggerModuleInitializationTest : BasePlatformTestCase() {
    fun testExportIsInitializedBeforeCallingFunctionAtBreakpoint() {
        val directory = Files.createTempDirectory("js-console-debug-init")
        val module = Files.writeString(directory.resolve("b.ts"), """
            export const b = 'ABC';

            export const initialText = `first line
            second line
            third line`;

            export function test(foo: string): string {
                if (!foo) return '';
                return foo.split('').reverse().join('');
            }
        """.trimIndent())
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module)!!
        @Suppress("UNCHECKED_CAST")
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.filterIsInstance<XLineBreakpointType<*>>()
            .first { it.id == "javascript" } as XLineBreakpointType<XBreakpointProperties<*>>
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        var breakpoint: com.intellij.xdebugger.breakpoints.XLineBreakpoint<XBreakpointProperties<*>>? = null
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create("C:/Program Files/nodejs/node.exe"))
        FileEditorManager.getInstance(project).openFile(file, true)
        val settings = JsConsoleSettings.getInstance()
        val originalDebugEnabled = settings.debugEnabled
        settings.debugEnabled = true
        breakpoint = WriteAction.compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<XBreakpointProperties<*>>, RuntimeException> {
            manager.addLineBreakpoint(type, file.url, 8, type.createBreakpointProperties(file, 8))
        }
        val service = project.getService(JsConsoleProjectService::class.java)
        val output = StringBuffer()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            PlatformTestUtil.waitWithEventsDispatching("Context not ready: $output", { "test" in service.contextNames }, 30)
            val startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            PlatformTestUtil.waitWithEventsDispatching("Startup settle", { System.nanoTime() >= startupDeadline }, 3)
            assertFalse("Unexpected startup pause at ${service.status}: $output", service.debugPaused)
            assertFalse("Breakpoint installation paused module: ${service.status}; output=$output", service.debugPaused)
            service.execute("test(b)")
            PlatformTestUtil.waitWithEventsDispatching("Breakpoint not reached: $output", { service.debugPaused || output.contains("ReferenceError") }, 30)
            assertTrue("Unexpected evaluation error: $output", service.debugPaused)
            PlatformTestUtil.waitWithEventsDispatching("Pause status missing: $output", { service.status.contains("paused at") }, 10)
            assertTrue("Stopped at wrong location: ${service.status}; output=$output", service.status.contains("b.ts:9"))
            val pausedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            PlatformTestUtil.waitWithEventsDispatching("Pause settle", { System.nanoTime() >= pausedDeadline }, 2)
            assertTrue("Pause was transient: ${service.status}; output=$output", service.debugPaused)
            val session = XDebuggerManager.getInstance(project).debugSessions.last()
            val frame = session.currentStackFrame ?: error("No stack frame at ${service.status}")
            val direct = PausedFrameEvaluator.evaluate(frame.evaluator ?: error("No evaluator"), "foo", session.currentPosition) { false }
            PlatformTestUtil.waitWithEventsDispatching("Direct evaluation did not complete: ${service.status}; output=$output", { direct.isDone }, 18)
            val directValue = direct.get(1, TimeUnit.SECONDS)
            assertTrue("Direct evaluation failed: $directValue", directValue.contains("ABC"))
            service.execute("foo")
            PlatformTestUtil.waitWithEventsDispatching("Local not evaluated; paused=${service.debugPaused}, status=${service.status}: $output", { output.contains("[2] ABC") || output.contains("[2] 'ABC'") || output.contains("[2] \"ABC\"") }, 20)
            service.resume()
            PlatformTestUtil.waitWithEventsDispatching("Call did not finish: $output", { output.contains("[1] 'CBA'") }, 20)
            PlatformTestUtil.waitWithEventsDispatching("Debugger did not resume", { !service.debugPaused }, 10)
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(document.text.replace("b = 'ABC'", "b = 'DEF'"))
            }
            service.execute("test(b)")
            PlatformTestUtil.waitWithEventsDispatching("Updated module did not stop: $output", {
                service.debugPaused || output.contains("[3] ReferenceError")
            }, 30)
            assertTrue("Updated module failed: $output", service.debugPaused)
            PlatformTestUtil.waitWithEventsDispatching("Updated pause status missing: $output", {
                service.status.contains("paused at")
            }, 10)
            assertTrue("Reload stopped before initialization: ${service.status}; output=$output", service.status.contains("b.ts:9"))
            service.execute("foo")
            PlatformTestUtil.waitWithEventsDispatching("Updated local not evaluated: $output", {
                output.contains("[4] DEF") || output.contains("[4] 'DEF'") || output.contains("[4] \"DEF\"")
            }, 20)
            service.resume()
            PlatformTestUtil.waitWithEventsDispatching("Updated call did not finish: $output", { output.contains("[3] 'FED'") }, 20)
        } finally {
            breakpoint?.let { WriteAction.run<RuntimeException> { manager.removeBreakpoint(it) } }
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.debugEnabled = originalDebugEnabled
            PlatformTestUtil.waitWithEventsDispatching("Debugger session did not stop", {
                XDebuggerManager.getInstance(project).debugSessions.none { it.sessionName == "JS Console" }
            }, 10)
            FileEditorManager.getInstance(project).closeFile(file)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Files.deleteIfExists(module)
            Files.deleteIfExists(directory)
        }
    }
}

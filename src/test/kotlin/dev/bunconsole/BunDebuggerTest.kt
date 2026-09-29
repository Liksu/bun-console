package dev.bunconsole

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterRef
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import dev.bunconsole.debug.BunDebugBridge
import dev.bunconsole.runtime.BunConsoleProcess
import dev.bunconsole.runtime.BunRuntimeLocator
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BunDebuggerTest : BasePlatformTestCase() {
    fun testBreakpointLocalsStepAndContinue() {
        val directory = Files.createTempDirectory("bun-console-debug-test")
        val module = Files.writeString(directory.resolve("debug-target.ts"), """
            export function twice(x: number) {
                const y = x * 2;
                return y;
            }
        """.trimIndent())
        val bootstrap = directory.resolve("bootstrap.mjs")
        javaClass.getResourceAsStream("/runtime/bootstrap.mjs")!!.use { Files.copy(it, bootstrap) }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(module)!!
        @Suppress("UNCHECKED_CAST")
        val type = XBreakpointType.EXTENSION_POINT_NAME.extensionList.filterIsInstance<XLineBreakpointType<*>>()
            .first { it.id == "javascript" } as XLineBreakpointType<XBreakpointProperties<*>>
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val breakpoint = WriteAction.compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<XBreakpointProperties<*>>, RuntimeException> {
            manager.addLineBreakpoint(type, file.url, 1, type.createBreakpointProperties(file, 1))
        }
        NodeJsInterpreterManager.getInstance(project).setInterpreterRef(NodeJsInterpreterRef.create("C:/Program Files/nodejs/node.exe"))
        val output = StringBuffer()
        var runtime: BunConsoleProcess? = null
        val debugger = BunDebugBridge(project, {}, { runtime?.close() })
        try {
            runtime = BunConsoleProcess(BunRuntimeLocator.locate(null), bootstrap, directory,
                { text, _ -> output.append(text) }, { output.append("\nSTOPPED: $it\n") }, debugger::attach)
            await(runtime.start(), output)
            await(runtime.request("load", mapOf("path" to module.toString())), output)
            val call = runtime.request("eval", mapOf("code" to "twice(21)"))
            PlatformTestUtil.waitWithEventsDispatching("Breakpoint not hit: $output", { debugger.paused }, 30)
            assertFalse("Call must remain pending at the breakpoint", call.isDone)
            assertEquals("21", await(debugger.evaluate("x"), output))
            debugger.session!!.stepOver(false)
            PlatformTestUtil.waitWithEventsDispatching("Step Over did not reach return: $output", {
                debugger.paused && debugger.positionText?.endsWith(":3") == true
            }, 20)
            assertEquals("42", await(debugger.evaluate("y"), output))
            debugger.session!!.resume()
            assertEquals("42", await(call, output).get("text").asString)
            assertEquals("6", await(runtime.request("eval", mapOf("code" to "2 * 3")), output).get("text").asString)
        } finally {
            WriteAction.run<RuntimeException> { manager.removeBreakpoint(breakpoint) }
            runtime?.close()
            debugger.close()
            runtime?.termination?.get(5, TimeUnit.SECONDS)
        }
    }
    private fun <T> await(future: CompletableFuture<T>, output: StringBuffer): T {
        PlatformTestUtil.waitWithEventsDispatching("Debugger operation timed out: $output", { future.isDone }, 55)
        return try { future.get(1, TimeUnit.SECONDS) } catch (error: Exception) { throw AssertionError("Debugger failed; output: $output", error) }
    }
}

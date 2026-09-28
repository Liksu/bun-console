package dev.jsconsole

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.settings.JsConsoleSettings
import java.util.concurrent.TimeUnit

class JsConsoleBusyTest : BasePlatformTestCase() {
    fun testPendingCommandsDoNotBlockAndABusyThreadIsReported() {
        val settings = JsConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = false
        val service = project.getService(JsConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            service.execute("await new Promise(() => {})")
            service.execute("40 + 2")
            awaitOutput(output, "[2] 42")
            PlatformTestUtil.waitWithEventsDispatching("Running status missing: ${service.status}", { "running…" in service.status }, 10)
            assertFalse(service.blocked)
            service.execute("Promise.reject(new Error('nobody waits')); 'scheduled'")
            awaitOutput(output, "[3] 'scheduled'")
            awaitOutput(output, "Uncaught (in promise) Error: nobody waits")
            service.execute("while (true) {}")
            PlatformTestUtil.waitWithEventsDispatching("Blocked thread not reported: ${service.status}", { service.blocked }, 15)
            assertTrue(service.status, "JavaScript is busy" in service.status)
            service.restart()
            assertFalse(service.blocked)
            service.execute("6 * 7")
            awaitOutput(output, "[5] 42")
            PlatformTestUtil.waitWithEventsDispatching("Running status stayed: ${service.status}", { "running…" !in service.status }, 5)
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.loadState(originalSettings)
        }
    }

    private fun awaitOutput(output: StringBuilder, expected: String) {
        try { PlatformTestUtil.waitWithEventsDispatching("Missing $expected", { output.contains(expected) }, 25) }
        catch (error: AssertionError) { throw AssertionError("Missing $expected; actual output: $output", error) }
    }
}

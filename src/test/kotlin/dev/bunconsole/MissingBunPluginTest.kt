package dev.bunconsole

import com.intellij.testFramework.PlatformTestUtil
import dev.bunconsole.debug.ConsoleDapArgumentsProvider
import dev.bunconsole.service.BunConsoleProjectService
import dev.bunconsole.settings.BunConsoleSettings
import java.util.concurrent.TimeUnit

/**
 * Without the Bun plugin (IntelliJ IDEA by default) the debugger cannot attach: the console says
 * what is needed and keeps working. Runs where the Bun plugin is absent (`-PplatformType=IU`).
 */
class MissingBunPluginTest : ConsoleTestCase() {
    fun testConsoleRunsWithoutTheDebuggerWhenTheBunPluginIsMissing() {
        if (ConsoleDapArgumentsProvider.bunDebuggerAvailable()) return // WebStorm: the Bun plugin is bundled.
        val settings = BunConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = true
        val service = project.getService(BunConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            service.attach({ text, _, _ -> output.append(text) }, {})
            service.execute("6 * 7")
            PlatformTestUtil.waitWithEventsDispatching("Console did not run without the debugger: $output", { output.contains("[1] 42") }, 30)
            assertTrue(output.toString(), output.contains("The debugger needs the Bun plugin"))
            assertFalse(output.toString(), output.contains("Bun Inspector"))
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            settings.loadState(originalSettings)
        }
    }
}

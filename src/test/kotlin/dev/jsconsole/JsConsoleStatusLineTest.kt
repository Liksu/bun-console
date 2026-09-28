package dev.jsconsole

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.settings.JsConsoleSettings
import dev.jsconsole.ui.JsConsolePanel
import java.util.concurrent.TimeUnit

/** The status must be visible in the panel itself, not only in the service. */
class JsConsoleStatusLineTest : BasePlatformTestCase() {
    fun testRunningAndBusyStatesAreShownAboveTheInput() {
        val settings = JsConsoleSettings.getInstance()
        val originalSettings = settings.state
        settings.debugEnabled = false
        val panel = JsConsolePanel(project)
        val service = project.getService(JsConsoleProjectService::class.java)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Runtime did not start: ${panel.statusText}", { panel.statusText == null }, 30)
            service.execute("await new Promise((resolve) => setTimeout(resolve, 1500))")
            PlatformTestUtil.waitWithEventsDispatching("Running state not shown: ${panel.statusText}", {
                panel.statusText?.startsWith("running…") == true
            }, 10)
            PlatformTestUtil.waitWithEventsDispatching("Status line stayed: ${panel.statusText}", { panel.statusText == null }, 10)
            service.execute("while (true) {}")
            PlatformTestUtil.waitWithEventsDispatching("Busy state not shown: ${panel.statusText}", {
                panel.statusText?.startsWith("JavaScript is busy") == true
            }, 15)
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            Disposer.dispose(panel)
            service.dispose()
            settings.loadState(originalSettings)
        }
    }
}

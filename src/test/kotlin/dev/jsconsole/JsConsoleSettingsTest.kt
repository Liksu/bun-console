package dev.jsconsole

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.jsconsole.runtime.BunRuntimeLocator
import dev.jsconsole.service.JsConsoleProjectService
import dev.jsconsole.settings.JsConsoleConfigurable
import dev.jsconsole.settings.JsConsoleSettings
import java.awt.Container
import java.util.concurrent.TimeUnit
import javax.swing.JCheckBox
import javax.swing.JTextField
import javax.swing.JRadioButton
import kotlin.test.assertFailsWith

class JsConsoleSettingsTest : BasePlatformTestCase() {
    fun testSettingsPersistValidateAndOnlyAffectTheNextRuntime() {
        assertFalse(JsConsoleSettings.Options().debugEnabled)
        val settings = JsConsoleSettings.getInstance()
        val original = settings.state
        val configurable = JsConsoleConfigurable()
        val service = project.getService(JsConsoleProjectService::class.java)
        val output = StringBuilder()
        try {
            settings.bunPath = ""
            settings.enterRuns = false
            settings.debugEnabled = false
            val form = configurable.createComponent()
            val checkboxes = descendants(form).filterIsInstance<JCheckBox>()
            val automatic = checkboxes.single { it.text.startsWith("Find Bun automatically") }
            val debugger = checkboxes.single { it.text == "Start console with debugger" }
            val executable = descendants(form).filterIsInstance<JTextField>().single()
            val inputKeys = descendants(form).filterIsInstance<JRadioButton>()
            assertEquals(2, inputKeys.size)
            assertTrue(inputKeys[0].isSelected)
            assertTrue(automatic.isSelected)
            assertFalse(debugger.isSelected)
            assertFalse(executable.isEnabled)
            assertFalse(configurable.isModified)
            service.attach({ text, _, _ -> output.append(text) }, {})
            service.execute("const kept = 23; kept")
            awaitOutput(output, "[1] 23")

            automatic.doClick()
            assertTrue(executable.isEnabled)
            assertFailsWith<ConfigurationException> { configurable.apply() }
            val bun = BunRuntimeLocator.locate(null)
            executable.text = bun
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals(bun, settings.bunPath)
            assertFalse(configurable.isModified)
            debugger.doClick()
            assertTrue(configurable.isModified)
            configurable.apply()
            assertTrue(settings.debugEnabled)
            val persisted = XmlSerializer.serialize(settings.state)
            val restored = JsConsoleSettings().apply {
                loadState(XmlSerializer.deserialize(persisted, JsConsoleSettings.Options::class.java))
            }
            assertEquals(bun, restored.bunPath)
            assertFalse(restored.enterRuns)
            assertTrue(restored.debugEnabled)
            debugger.doClick()
            configurable.apply()
            assertFalse(settings.debugEnabled)
            inputKeys[1].doClick()
            assertTrue(configurable.isModified)
            configurable.apply()
            assertTrue(settings.enterRuns)
            assertTrue(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), JsConsoleSettings.Options::class.java).enterRuns)
            assertFalse(configurable.isModified)
            service.execute("kept")
            awaitOutput(output, "[2] 23")
            assertEquals(1, Regex("Starting fresh runtime").findAll(output).count())

            executable.text = "relative-bun.exe"
            assertFailsWith<ConfigurationException> { configurable.apply() }
            executable.text = "Z:/missing-js-console-bun.exe"
            assertFailsWith<ConfigurationException> { configurable.apply() }
            assertEquals(bun, settings.bunPath)
            configurable.reset()
            assertEquals(bun, executable.text)
            assertTrue(inputKeys[1].isSelected)
            assertFalse(configurable.isModified)

            // Simulate an executable removed after it was saved in IDE settings.
            settings.bunPath = "Z:/missing-js-console-bun.exe"
            service.execute("kept + 1")
            awaitOutput(output, "[3] 24")
            service.restart()
            awaitOutput(output, "Bun executable not found or not executable")
            configurable.reset()
            automatic.doClick()
            configurable.apply()
            assertEquals("", settings.bunPath)
            assertFalse(executable.isEnabled)
            service.restart()
            service.execute("typeof kept")
            awaitOutput(output, "[4] 'undefined'")
        } finally {
            service.stop().get(5, TimeUnit.SECONDS)
            service.dispose()
            configurable.disposeUIResources()
            settings.loadState(original)
        }
    }

    private fun awaitOutput(output: StringBuilder, expected: String) {
        PlatformTestUtil.waitWithEventsDispatching("Missing $expected: $output", { output.contains(expected) }, 20)
    }
    private fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}

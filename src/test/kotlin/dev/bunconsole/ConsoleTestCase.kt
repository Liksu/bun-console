package dev.bunconsole

import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.EnumSet

/**
 * Base for the IDE tests. In the IntelliJ IDEA test run (`-PplatformType=IU`), IDEA's licensing
 * component (plugin `com.intellij.modules.ultimate`) cannot start headless and logs an error while
 * the test project opens, which the test framework would report as a failure of every test.
 * Only errors from that component are ignored, and only in that run.
 */
abstract class ConsoleTestCase : BasePlatformTestCase() {
    private var licensingErrors: AccessToken? = null

    override fun setUp() {
        if (System.getProperty("bun.console.testIdea") == "true") {
            licensingErrors = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
                override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> {
                    val fromLicensing = (sequenceOf(message) + generateSequence(t) { it.cause }.map { it.message.orEmpty() })
                        .any { ULTIMATE_LICENSING in it }
                    return if (fromLicensing) EnumSet.noneOf(Action::class.java) else super.processError(category, message, details, t)
                }
            })
        }
        super.setUp()
    }

    override fun tearDown() {
        try {
            stopDebugAdapter()
            super.tearDown()
        } finally { licensingErrors?.finish() }
    }

    /**
     * The Bun plugin's debug adapter is one Node.js server for the whole IDE, kept between debug
     * sessions. It runs in the folder of the project that started it, which Windows cannot delete
     * while it runs, so the test project's teardown would fail. Bun itself runs as bun.exe.
     */
    private fun stopDebugAdapter() {
        val adapters = ProcessHandle.current().descendants()
            .filter { it.info().command().orElse("").endsWith("node.exe", ignoreCase = true) }.toList()
        adapters.forEach { it.destroyForcibly() }
        PlatformTestUtil.waitWithEventsDispatching("Debug adapter did not exit", { adapters.none { it.isAlive } }, 10)
    }

    private companion object {
        const val ULTIMATE_LICENSING = "com.intellij.modules.ultimate"
    }
}

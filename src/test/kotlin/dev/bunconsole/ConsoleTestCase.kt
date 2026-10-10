package dev.bunconsole

import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LoggedErrorProcessor
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
        try { super.tearDown() } finally { licensingErrors?.finish() }
    }

    private companion object {
        const val ULTIMATE_LICENSING = "com.intellij.modules.ultimate"
    }
}

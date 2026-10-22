import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.bunconsole"
version = "0.3.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// -PplatformType=IU builds and tests against IntelliJ IDEA Ultimate instead of WebStorm.
val testIdea = providers.gradleProperty("platformType").orNull == "IU"
val platformPrefix = if (testIdea) "IU" else "WS"

dependencies {
    intellijPlatform {
        val idePath = providers.gradleProperty("webstormPath")
        when {
            testIdea -> create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
            idePath.isPresent -> local(idePath.get())
            else -> webstorm("2026.2.3")
        }
        bundledPlugin("JavaScript")
        // The Bun plugin is optional at runtime (only the debugger uses its adapter); IntelliJ IDEA
        // does not bundle it, so the IDEA test run goes without it, like a user's IDEA would.
        if (!testIdea) bundledPlugin("intellij.javascript.bun")
        bundledModule("intellij.platform.dap")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    if (providers.gradleProperty("apiAudit").isPresent) {
        compileOnly(files("build/phase-0/api/javascript-bun.jar"))
    }
}

// Negative compilation probe only; excluded from normal builds and packaging.
if (providers.gradleProperty("apiAudit").isPresent) {
    kotlin.sourceSets.main { kotlin.srcDir("spikes/jetbrains-api") }
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY }
}

// Light IDE fixtures reuse a project within a JVM; these lifecycle tests need
// independent project services and temporary project directories per class.
tasks.test {
    forkEvery = 1
    maxParallelForks = 1
    // Same development trace as runIde, to diagnose failing IDE tests.
    systemProperty("bun.console.trace", layout.buildDirectory.file("bun-console-trace-test.log").get().asFile.absolutePath)
    systemProperty("bun.console.testIdea", testIdea.toString())
    // winp (used to end process trees) unpacks its helpers next to the IDE by default, which is
    // Gradle's immutable copy of the IDE: the next build would refuse to use the modified copy.
    systemProperty("winp.folder.preferred", layout.buildDirectory.dir("winp").get().asFile.absolutePath)
    // Without the Bun plugin (IntelliJ IDEA) there is no debug adapter to test.
    if (testIdea) filter {
        excludeTestsMatching("dev.bunconsole.BunDebugger*")
        excludeTestsMatching("dev.bunconsole.BunLateBreakpointTest")
        excludeTestsMatching("dev.bunconsole.ExternalPausedSessionTest")
    }
    doFirst {
        // Live Edit has an incomplete headless fixture in this IDE installation.
        val disabled = layout.buildDirectory.file("isolated-ide/${rootProject.name}/$platformPrefix-2026.2.3/config-test/disabled_plugins.txt").get().asFile
        disabled.parentFile.mkdirs()
        val liveEdit = "com.intellij.plugins.html.instantEditing"
        if (!disabled.exists() || liveEdit !in disabled.readLines()) disabled.appendText("$liveEdit\n")
    }
}

// The sandbox IDE writes a development trace of the console (input, output, status, runtime traffic).
tasks.named<JavaExec>("runIde") {
    systemProperty("bun.console.trace", layout.buildDirectory.file("bun-console-trace.log").get().asFile.absolutePath)
}

intellijPlatform {
    buildSearchableOptions = false
    sandboxContainer = layout.buildDirectory.dir("isolated-ide")
    pluginConfiguration {
        // Open-ended: only the optional debugger uses Experimental API, and it
        // degrades to console-only mode if that API changes (see docs/marketplace.md).
        ideaVersion { sinceBuild = "262"; untilBuild = provider { null } }
    }
    pluginVerification {
        failureLevel = VerifyPluginTask.FailureLevel.ALL.filterNot { it == VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES }
        ides {
            current()
            // IntelliJ IDEA Ultimate has no Bun plugin: the verifier checks that the plugin still loads there.
            if (!testIdea) create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
            // -PverifyIdes=PhpStorm,GoLand,… also checks other IDEs with the JavaScript plugin (latest 2026.2 release of each).
            providers.gradleProperty("verifyIdes").orNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { names ->
                select {
                    types = names.map { name -> IntelliJPlatformType.entries.first { it.name.equals(name, ignoreCase = true) } }
                    channels = listOf(ProductRelease.Channel.RELEASE)
                    sinceBuild = "262"
                    untilBuild = "262.*"
                }
            }
            // -PverifyEap also checks the next WebStorm EAP (APIs scheduled for removal show up there first).
            if (providers.gradleProperty("verifyEap").isPresent) {
                select {
                    types = listOf(IntelliJPlatformType.WebStorm)
                    channels = listOf(ProductRelease.Channel.EAP)
                    sinceBuild = "263"
                }
            }
        }
    }
}

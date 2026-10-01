import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.bunconsole"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val idePath = providers.gradleProperty("webstormPath")
        if (idePath.isPresent) local(idePath.get()) else webstorm("2026.2.3")
        bundledPlugin("JavaScript")
        bundledPlugin("intellij.javascript.bun")
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
    doFirst {
        // Live Edit has an incomplete headless fixture in this IDE installation.
        val disabled = layout.buildDirectory.file("isolated-ide/${rootProject.name}/WS-2026.2.3/config-test/disabled_plugins.txt").get().asFile
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
        ides { current() }
    }
}

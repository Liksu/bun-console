import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.jsconsole"
version = "0.1.15-dev"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val idePath = providers.gradleProperty("webstormPath")
        if (idePath.isPresent) local(idePath.get()) else webstorm("2026.2.3")
        bundledPlugin("JavaScript")
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
tasks.test { forkEvery = 1; maxParallelForks = 1 }

intellijPlatform {
    buildSearchableOptions = false
    sandboxContainer = layout.buildDirectory.dir("isolated-ide")
    pluginConfiguration {
        ideaVersion { sinceBuild = "262"; untilBuild = "262.*" }
    }
    pluginVerification {
        failureLevel = VerifyPluginTask.FailureLevel.ALL
        ides { current() }
    }
}

package dev.jsconsole.spike

import com.intellij.javascript.bun.runConfiguration.run.BunRunConfiguration
import com.intellij.javascript.bun.runConfiguration.run.BunRunConfigurationType

// No visibility suppressions. Compiler rejection is evidence, not a workaround.
fun inspectBunConfiguration(configuration: BunRunConfiguration): String? = configuration.filePath
fun inspectBunType(): BunRunConfigurationType = BunRunConfigurationType()

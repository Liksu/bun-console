package dev.bunconsole.debug

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.platform.dap.LaunchRequestArguments

/** Experimental DAP facade approved for this integration; no Bun implementation classes. */
class ConsoleDebugProfile(val inspectorUrl: String) : RunProfile {
    override fun getName() = "Bun Console"
    override fun getIcon(): javax.swing.Icon? = null
    override fun getState(executor: Executor, environment: ExecutionEnvironment) = RunProfileState { _, _ -> null }
}

class ConsoleDapArgumentsProvider : DapLaunchArgumentsProvider {
    override fun isApplicable(executorId: String, profile: RunProfile) =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is ConsoleDebugProfile

    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments {
        val adapter = DebugAdapterSupportProvider.EP_NAME.extensionList.firstOrNull { it.adapterId.type == "bun" }
            ?: error("WebStorm's Bun debugger is unavailable. Enable the bundled Bun plugin.")
        // The Bun adapter attaches to an already-running inspector URL.
        return LaunchRequestArguments(adapter.adapterId, DapStartRequest.Attach,
            mapOf("url" to (profile as ConsoleDebugProfile).inspectorUrl))
    }
}

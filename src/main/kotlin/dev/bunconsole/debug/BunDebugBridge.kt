package dev.bunconsole.debug

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.platform.dap.DapProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only the debugger session attached to one console process. */
class BunDebugBridge(
    private val project: Project,
    private val changed: () -> Unit,
    private val stopped: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    var session: XDebugSession? = null
        private set
    val paused: Boolean get() = session?.isSuspended == true && !closed.get()
    val positionText: String? get() = if (paused) session?.currentPosition?.let { "${it.file.name}:${it.line + 1}" } else null
    private var revision = 0L
    private var runtimeReady = false
    private var debugWasVisible = false
    private val evaluations = mutableSetOf<CompletableFuture<String>>()

    fun attach(url: String): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        onEdt {
            try {
                check(!closed.get() && !project.isDisposed) { "Console debugger startup cancelled" }
                check(session == null) { "Debugger already attached" }
                val windows = ToolWindowManager.getInstance(project)
                debugWasVisible = windows.getToolWindow("Debug")?.isVisible == true
                val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                val profile = ConsoleDebugProfile(url)
                val environment = ExecutionEnvironmentBuilder.create(project, executor, profile).build()
                val args = ConsoleDapArgumentsProvider().getLaunchArguments(project, profile)
                val starter = DapProcessStarter(environment, executor, profile.getState(executor, environment),
                    args.adapterId, args.request, args.arguments)
                val started = XDebuggerManager.getInstance(project).newSessionBuilder(starter)
                    .environment(environment).sessionName("Bun Console")
                    .showTab(true).showToolWindowOnSuspendOnly(false).startSession().session
                session = started
                started.addSessionListener(object : XDebugSessionListener {
                    override fun sessionPaused() = onEdt {
                        if (!closed.get()) {
                            changed()
                            if (runtimeReady) windows.getToolWindow("Debug")?.activate(null)
                        }
                    }
                    override fun stackFrameChanged() = onEdt { invalidateEvaluations(); if (!closed.get()) changed() }
                    override fun beforeSessionResume() = onEdt { invalidateEvaluations() }
                    override fun sessionResumed() = onEdt { invalidateEvaluations(); if (!closed.get()) changed() }
                    override fun sessionStopped() = onEdt {
                        invalidateEvaluations()
                        if (closed.compareAndSet(false, true)) { changed(); stopped() }
                    }
                })
                result.complete(Unit)
            } catch (error: Throwable) {
                // LinkageError too: the Experimental DAP API may change in a future IDE build.
                result.completeExceptionally(error)
                close()
            }
        }
        return result
    }

    /** Keep the DAP session initialized, but return focus to the console once Bun is ready. */
    fun runtimeReady() = onEdt {
        if (closed.get() || project.isDisposed) return@onEdt
        runtimeReady = true
        if (paused) return@onEdt
        val windows = ToolWindowManager.getInstance(project)
        val showConsole = {
            windows.getToolWindow("Bun Console")?.let { console ->
                console.show { console.activate(null) }
            }
            Unit
        }
        val debug = windows.getToolWindow("Debug")
        if (!debugWasVisible && debug?.isVisible == true) debug.hide(showConsole)
        else showConsole()
    }

    fun evaluate(source: String): CompletableFuture<String> {
        check(ApplicationManager.getApplication().isDispatchThread)
        val current = session
        if (!paused || current == null) return CompletableFuture.failedFuture(IllegalStateException("Debugger is not paused"))
        val frame = current.currentStackFrame
        val evaluator = frame?.evaluator
            ?: return CompletableFuture.failedFuture(IllegalStateException("Select a stack frame with JavaScript evaluation support"))
        val epoch = revision
        val result = PausedFrameEvaluator.evaluate(evaluator, source, current.currentPosition) {
            closed.get() || epoch != revision || !current.isSuspended || current.currentStackFrame !== frame
        }
        evaluations.add(result)
        result.whenComplete { _, _ -> onEdt { evaluations.remove(result) } }
        return result
    }

    private fun invalidateEvaluations() {
        revision++
        evaluations.toList().forEach { it.completeExceptionally(IllegalStateException("Debugger frame changed or resumed")) }
        evaluations.clear()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onEdt { invalidateEvaluations(); session?.stop(); session = null }
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeLater(action, ModalityState.nonModal())
    }
}

package dev.bunconsole.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.openapi.vfs.LocalFileSystem
import com.google.gson.JsonObject
import dev.bunconsole.debug.BunDebugBridge
import dev.bunconsole.debug.PausedFrameEvaluator
import dev.bunconsole.debug.PausedFrameNames
import dev.bunconsole.runtime.BunConsoleProcess
import dev.bunconsole.runtime.BunRuntimeLocator
import dev.bunconsole.runtime.BootstrapManager
import dev.bunconsole.runtime.ConsoleFiles
import dev.bunconsole.runtime.ConsoleImports
import dev.bunconsole.runtime.ConsoleOutput
import dev.bunconsole.runtime.ConsoleTrace
import dev.bunconsole.runtime.TopLevelDeclarations
import dev.bunconsole.settings.BunConsoleSettings
import dev.bunconsole.ui.ConsoleStyle
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class BunConsoleProjectService(private val project: Project) : Disposable {
    private companion object {
        val SHOW_RUNNING_AFTER = TimeUnit.MILLISECONDS.toNanos(100)
        val PING_AFTER = TimeUnit.SECONDS.toNanos(1)
        const val DEBUGGER_ATTACH_FAILED = "Debugger could not attach"
        val JAVASCRIPT_FRAME_EXTENSIONS = setOf("js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx", "vue", "svelte", "astro", "html", "htm")
    }
    private val background = Executors.newSingleThreadExecutor { task -> Thread(task, "Bun Console lifecycle").apply { isDaemon = true } }
    enum class OutputKind { NORMAL, ERROR, WARNING, JAVASCRIPT, RESULT, CLEAR }
    private data class Entry(val text: String, val kind: OutputKind, val styles: List<ConsoleStyle>)
    private val entries = mutableListOf<Entry>()
    private var append: ((String, OutputKind, List<ConsoleStyle>) -> Unit)? = null
    private var statusChanged: ((String) -> Unit)? = null
    private var generation = 0L
    private var command = 0L
    private var runtime: BunConsoleProcess? = null
    private var debugger: BunDebugBridge? = null
    /**
     * The suspended JavaScript session that console input evaluates in, as in
     * DevTools: this console's own runtime first, otherwise any JavaScript
     * program the user is debugging in this project (the selected session first).
     */
    private fun pausedSession(): XDebugSession? {
        debugger?.takeIf { it.paused }?.session?.let { return it }
        val manager = XDebuggerManager.getInstance(project)
        return (listOfNotNull(manager.currentSession) + manager.debugSessions).firstOrNull { session ->
            session !== debugger?.session && session.isSuspended && session.currentStackFrame?.evaluator != null &&
                session.currentPosition?.file?.extension?.lowercase() in JAVASCRIPT_FRAME_EXTENSIONS
        }
    }
    val debugPaused: Boolean get() = pausedSession() != null
    fun resume() { pausedSession()?.resume() }
    fun stepOver() { pausedSession()?.stepOver(false) }
    fun stepInto() { pausedSession()?.stepInto() }
    fun stepOut() { pausedSession()?.stepOut() }

    /** Names in scope where execution is paused, for completion; empty while running. Call in a read action. */
    fun pausedFrameNames(): Set<String> =
        pausedSession()?.currentPosition?.let { PausedFrameNames.at(project, it) }.orEmpty()

    private fun pausedLabel(): String {
        val session = pausedSession() ?: return ""
        if (session === debugger?.session) return " · paused at ${debugger?.positionText ?: "selected frame"}"
        val position = session.currentPosition?.let { " at ${it.file.name}:${it.line + 1}" }.orEmpty()
        return " · paused in ${session.sessionName}$position"
    }

    /** Pauses of the user's own debug sessions change where input goes; keep the header in sync. */
    private val sessionWatcher = object : XDebugSessionListener {
        private fun changed() = ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) updateContextStatus()
        }, ModalityState.nonModal())
        override fun sessionPaused() = changed()
        override fun sessionResumed() = changed()
        override fun sessionStopped() = changed()
        override fun stackFrameChanged() = changed()
    }
    private var ready: CompletableFuture<BunConsoleProcess>? = null
    private var contextReady: CompletableFuture<BunConsoleProcess>? = null
    private var context: VirtualFile? = null
    private data class FileBinding(val exported: String, val local: String)
    private sealed interface Addition {
        data class Symbol(val path: String, val exported: String, val local: String) : Addition
        data class File(val path: String, val bindings: List<FileBinding>) : Addition
    }
    private val additions = mutableListOf<Addition>()
    private val stalePaths = mutableSetOf<String>()
    private val restartPaths = mutableSetOf<String>()
    private val modifiedVersions = mutableMapOf<String, Long>()
    private val editedPaths = mutableSetOf<String>()
    private val knownContextPaths = mutableSetOf<String>()
    private val pendingBreakpointUpdates = ConcurrentHashMap<XBreakpoint<*>, CompletableFuture<Void>>()
    private var editVersion = 0L
    private var refreshing = false
    @Volatile private var addedFilePaths: Set<String> = emptySet()
    private var disposed = false
    /** Set when the IDE's debugger could not attach; the console then runs without it until toggled again. */
    private var debuggerFailed = false
    val pinned: Boolean get() = context?.path in addedFilePaths
    val hasCurrentFile: Boolean get() = context != null
    val history = mutableListOf<String>()
    private var currentNames = emptySet<String>()
    private var addedNames = emptySet<String>()
    /** Globals defined by console input, as reported by the runtime after each command. */
    private var consoleNames = emptySet<String>()
    @Volatile var contextNames: Set<String> = emptySet()
        private set
    var status: String = "Stopped"
        private set
    val restartRequired: Boolean get() = restartPaths.isNotEmpty()
    /** Width of the output in characters; the runtime lays out results for it. */
    @Volatile var outputColumns = 120
    /** Console commands still waiting for their result, by command number, with start times. */
    private val running = linkedMapOf<Long, Long>()
    private var busyTimer: Timer? = null
    private var ping: CompletableFuture<JsonObject>? = null
    private var pingSent = 0L
    /** True when Bun did not answer a ping: synchronous code occupies its JavaScript thread. */
    var blocked = false
        private set

    data class ContextTab(val key: String, val label: String, val path: String?, val active: Boolean)

    fun contextTabs(): List<ContextTab> {
        val pinnedPaths = additions.filterIsInstance<Addition.File>().map(Addition.File::path).distinct()
        val currentPath = context?.path
        val paths = (pinnedPaths + listOfNotNull(currentPath)).distinct()
        val labels = shortestUniqueFileLabels(paths)
        // Tabs only name the files; the pause location goes to the status line, which has room for it.
        val tabs = pinnedPaths.map { path ->
            ContextTab(path, "${labels.getValue(path)} · pinned", path, path == currentPath)
        }
        return if (currentPath != null && currentPath !in pinnedPaths) {
            tabs + ContextTab(currentPath, "${labels.getValue(currentPath)} · follows editor", currentPath, true)
        } else if (currentPath == null) {
            tabs + ContextTab("plain-javascript", "JavaScript · follows editor", null, true)
        } else tabs
    }

    private fun shortestUniqueFileLabels(paths: List<String>): Map<String, String> {
        val components = paths.associateWith { it.replace('\\', '/').split('/').filter(String::isNotEmpty) }
        return paths.associateWith { path ->
            val parts = components.getValue(path)
            (1..parts.size).firstNotNullOfOrNull { count ->
                val suffix = parts.takeLast(count)
                suffix.joinToString("/").takeIf { label ->
                    components.values.count { it.takeLast(count).joinToString("/") == label } == 1
                }
            } ?: path.replace('\\', '/')
        }
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val changed = FileDocumentManager.getInstance().getFile(event.document) ?: return
                // Only real files of this project (or its console context) can affect the runtime;
                // the console input and other projects' documents cannot.
                if (ready == null || !ConsoleFiles.isSource(changed)) return
                if (changed != context && changed.path !in knownContextPaths &&
                    !ProjectFileIndex.getInstance(project).isInContent(changed)) return
                val markChanged = {
                    modifiedVersions[changed.path] = ++editVersion
                    editedPaths.add(changed.path)
                    if (changed == context || changed.path in addedFilePaths) {
                        stalePaths.add(changed.path)
                        updateContextStatus()
                    }
                }
                if (ApplicationManager.getApplication().isDispatchThread) markChanged()
                else onEdt(generation) { markChanged() }
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (ready != null) followEditor()
            }
        })
        project.messageBus.connect(this).subscribe(XBreakpointListener.TOPIC, object : XBreakpointListener<XBreakpoint<*>> {
            override fun breakpointAdded(breakpoint: XBreakpoint<*>) = refreshForBreakpoint(breakpoint)
            override fun breakpointChanged(breakpoint: XBreakpoint<*>) = refreshForBreakpoint(breakpoint)
            override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
                pendingBreakpointUpdates.remove(breakpoint)?.complete(null)
            }
            override fun breakpointPresentationUpdated(breakpoint: XBreakpoint<*>, session: XDebugSession?) {
                if (session == debugger?.session) pendingBreakpointUpdates.remove(breakpoint)?.complete(null)
            }
        })
        XDebuggerManager.getInstance(project).debugSessions.forEach { it.addSessionListener(sessionWatcher, this) }
        project.messageBus.connect(this).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                debugProcess.session.addSessionListener(sessionWatcher, this@BunConsoleProjectService)
            }
            override fun currentSessionChanged(previousSession: XDebugSession?, currentSession: XDebugSession?) {
                ApplicationManager.getApplication().invokeLater({
                    if (!disposed && !project.isDisposed) updateContextStatus()
                }, ModalityState.nonModal())
            }
        })
    }

    private fun refreshForBreakpoint(breakpoint: XBreakpoint<*>) {
        if (debugger == null) return
        val line = breakpoint as? XLineBreakpoint<*> ?: return
        if (line.type.id != "javascript" || !line.isEnabled) return
        val file = VirtualFileManager.getInstance().findFileByUrl(line.fileUrl) ?: return
        val epoch = generation
        val refresh = {
            if (ready != null && file.path in knownContextPaths &&
                (file == context || file.path in addedFilePaths)) {
                // The DAP adapter registers new breakpoints asynchronously. Keep
                // the next evaluation behind its presentation update and reload
                // this module only after the adapter has accepted the breakpoint.
                val gate = CompletableFuture<Void>()
                pendingBreakpointUpdates.put(line, gate)?.complete(null)
                contextReady = contextReady?.thenCombine(gate) { backend, _ -> backend }
                // Editing a file moves its breakpoints too; like any edit, this is
                // applied when the console gets focus or runs a command, not while typing.
                modifiedVersions[file.path] = ++editVersion
                CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute {
                    pendingBreakpointUpdates.remove(line, gate)
                    gate.complete(null)
                }
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) refresh()
        else onEdt(epoch) { refresh() }
    }

    fun attach(append: (String, OutputKind, List<ConsoleStyle>) -> Unit, statusChanged: (String) -> Unit) {
        this.append = append
        this.statusChanged = statusChanged
        entries.forEach { (text, kind, styles) -> append(text, kind, styles) }
        statusChanged(status)
        if (ready == null) restart()
    }

    fun detach() { append = null; statusChanged = null }

    fun clearOutput() {
        entries.clear()
        append?.invoke("", OutputKind.CLEAR, emptyList())
    }

    fun stop(): CompletableFuture<*> {
        generation++
        resetRunning()
        pendingBreakpointUpdates.values.forEach { it.complete(null) }
        pendingBreakpointUpdates.clear()
        runtime?.close()
        debugger?.close()
        debugger = null
        return runtime?.termination ?: CompletableFuture.completedFuture(null)
    }

    fun openContextFile(path: String) {
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        if (file.isValid) FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** Debugging needs Bun's inspector from process start, so switching it restarts only the runtime. */
    fun setDebuggerEnabled(enabled: Boolean) {
        val settings = BunConsoleSettings.getInstance()
        if (settings.debugEnabled == enabled && (debugger != null) == enabled) return
        settings.debugEnabled = enabled
        debuggerFailed = false
        write(if (enabled) "\nDebugger on: restarting runtime; breakpoints stop console calls\n"
            else "\nDebugger off: restarting runtime without the debugger\n")
        restart()
    }

    fun togglePin() {
        val file = context ?: return
        if (isFileAdded(file)) removeFile(file) else addFile(file)
    }

    fun restart() {
        val epoch = ++generation
        pendingBreakpointUpdates.values.forEach { it.complete(null) }
        pendingBreakpointUpdates.clear()
        stalePaths.clear()
        restartPaths.clear()
        modifiedVersions.clear()
        editedPaths.clear()
        knownContextPaths.clear()
        refreshing = false
        resetRunning()
        runtime?.close()
        debugger?.close()
        debugger = null
        runtime = null
        currentNames = emptySet()
        addedNames = emptySet()
        consoleNames = emptySet()
        contextNames = emptySet()
        context = activeFile()
        updateStatus("Starting Bun…")
        write("\n— Starting fresh runtime —\n")
        val selectedBun = BunConsoleSettings.getInstance().bunPath
        val startup = CompletableFuture.supplyAsync({
            val executable = BunRuntimeLocator.locate(selectedBun)
            val script = BootstrapManager.materialize()
            val cwd = Path.of(project.basePath ?: System.getProperty("user.home"))
            val bridge = if (BunConsoleSettings.getInstance().debugEnabled && !debuggerFailed) {
                BunDebugBridge(project,
                    { onEdt(epoch) { updateContextStatus() } },
                    { onEdt(epoch) {
                        debugger = null
                        updateContextStatus()
                        write("Debugger disconnected; console continues without breakpoints. Restart Runtime to reconnect\n")
                    } })
            } else null
            val backend = BunConsoleProcess(executable, script, cwd,
                { text, stream -> onEdt(epoch) {
                    when (stream) {
                        ConsoleOutput.NORMAL -> writeEntry(text, OutputKind.NORMAL)
                        ConsoleOutput.ERROR -> writeEntry(text, OutputKind.ERROR)
                        ConsoleOutput.WARNING -> writeEntry(text, OutputKind.WARNING)
                        ConsoleOutput.CLEAR -> clearOutput()
                    }
                } },
                { reason -> onEdt(epoch) {
                    debugger?.close()
                    if (bridge != null && reason.startsWith(DEBUGGER_ATTACH_FAILED)) {
                        debuggerFailed = true
                        write("$reason\nContinuing without the debugger; switch Debugger off and on to retry\n", true)
                        restart()
                    } else {
                        updateStatus("Stopped · $reason")
                        write("$reason\n", true)
                    }
                } },
                bridge?.let { it::attach })
            backend to bridge
        }, background).thenCompose { (backend, bridge) ->
            val launched = CompletableFuture<BunConsoleProcess>()
            onEdt(epoch, { backend.close(); bridge?.close(); launched.completeExceptionally(IllegalStateException("Startup superseded")) }) {
                runtime = backend
                debugger = bridge
                backend.start().whenComplete { value, error ->
                    if (error == null) { bridge?.runtimeReady(); launched.complete(value) }
                    else { bridge?.close(); launched.completeExceptionally(error) }
                }
            }
            launched
        }
        ready = startup
        loadContext(startup, epoch)
    }

    fun execute(source: String, styles: List<ConsoleStyle> = emptyList()) {
        if (source.isBlank()) return
        ConsoleTrace.log("INPUT", source + (pausedSession()?.let { "   [paused frame: ${it.sessionName}]" } ?: ""))
        if (!debugPaused) refreshContextIfNeeded()
        val id = ++command
        history.add(source)
        if (history.size > 500) history.removeAt(0)
        write("\n[$id] > ")
        writeEntry(source, OutputKind.JAVASCRIPT, styles)
        write("\n")
        val epoch = generation
        val paused = pausedSession()
        if (paused != null) {
            val own = debugger?.takeIf { paused === it.session }
            val result = own?.evaluate(source) ?: PausedFrameEvaluator.evaluateIn(paused, source)
            result.whenComplete { value, error -> onEdt(epoch) {
                if (error != null) write("[$id] ${message(error)}\n", true)
                else { write("[$id] "); writeEntry(value, OutputKind.RESULT); write("\n") }
            } }
            return
        }
        val backend = contextReady ?: return
        running[id] = System.nanoTime()
        startBusyTimer()
        backend.thenComposeAsync({
            val evaluation = ReadAction.computeBlocking<ConsoleImports.Evaluation, RuntimeException> {
                ConsoleImports.prepare(project, source)
            }
            it.request("eval", mapOf("code" to evaluation.code, "imports" to evaluation.imports,
                "declarations" to evaluation.declarations, "columns" to outputColumns))
        }, background)
            .whenComplete { result, error -> onEdt(epoch) {
                running.remove(id)
                result?.getAsJsonArray("globals")?.let { globals ->
                    consoleNames = globals.map { it.asString }.toSet()
                    refreshCompletionNames()
                }
                if (error != null) write("[$id] ${message(error)}\n", true)
                else {
                    write("[$id] ")
                    writeEntry(result.get("text")?.asString ?: "undefined", OutputKind.RESULT)
                    write("\n")
                }
                updateBusyStatus()
            } }
    }

    private fun startBusyTimer() {
        val timer = busyTimer ?: Timer(250) { updateBusyStatus() }.also { busyTimer = it }
        if (!timer.isRunning) timer.start()
    }

    private fun resetRunning() {
        running.clear()
        busyTimer?.stop()
        ping = null
        blocked = false
    }

    /**
     * Awaited commands may run for a long time without blocking the console. A
     * ping answered outside Bun's command queue tells them apart from synchronous
     * code that occupies the JavaScript thread (only Restart can stop that).
     */
    private fun updateBusyStatus() {
        if (running.isEmpty()) {
            resetRunning()
            updateContextStatus()
            return
        }
        val now = System.nanoTime()
        val probe = ping
        when {
            debugPaused -> { ping = null; blocked = false }
            probe == null -> if (now - running.values.first() >= PING_AFTER) {
                ping = runtime?.request("ping")
                pingSent = now
            }
            probe.isDone -> { ping = null; blocked = false }
            now - pingSent >= PING_AFTER -> blocked = true
        }
        val text = contextStatusText()
        if (text != status) updateStatus(text)
    }

    /** Reimport edited contexts, or restart the console runtime when an enabled breakpoint must be rebound. */
    fun refreshContextIfNeeded() {
        if (debugPaused || refreshing || modifiedVersions.isEmpty() || ready == null) return
        val previous = contextReady ?: return
        val epoch = generation
        // A previously selected file can wait until it becomes the current
        // context again; it does not require a full runtime restart now.
        val versions = modifiedVersions.filterKeys {
            it == context?.path || it in addedFilePaths || it !in knownContextPaths
        }
        if (versions.isEmpty()) return
        val direct = versions.keys.filter { it == context?.path || it in addedFilePaths }
        val debugEdit = if (debugger != null) direct.firstOrNull {
            it in editedPaths && hasEnabledJavaScriptBreakpoint(it)
        } else null
        if (debugEdit != null) {
            write("Restarting runtime to keep breakpoints synchronized after ${Path.of(debugEdit).fileName} changed\n")
            restart()
            return
        }
        refreshing = true
        val current = context
        val next = previous.thenComposeAsync({ backend ->
            backend.request("cached_files").handle { result, error ->
                val cached = if (error == null) result.getAsJsonArray("paths").map { normalizePath(it.asString) }.toSet()
                    else emptySet()
                val dependent = versions.keys.filter { it !in direct && normalizePath(it) in cached }
                dependent to error
            }.thenCompose { (dependent, cacheError) ->
                var chain = CompletableFuture.completedFuture(emptyList<ReloadResult>())
                for (path in direct) {
                    chain = chain.thenCompose { prior ->
                        val file = (current?.takeIf { it.path == path } ?: LocalFileSystem.getInstance().findFileByPath(path))
                        if (file == null || !file.isValid) {
                            CompletableFuture.completedFuture(prior + ReloadResult(path, null, IllegalStateException("File unavailable")))
                        } else {
                            saveContext(file, epoch).thenComposeAsync({
                                backend.request("reload_file", mapOf("path" to path) + declarationFields(path))
                            }, background)
                                .handle { result, error -> prior + ReloadResult(path, result, error) }
                        }
                    }
                }
                chain.thenApply { results ->
                    onEdt(epoch) {
                        for (path in dependent) restartPaths.add(path)
                        if (cacheError != null) restartPaths.addAll(versions.keys)
                        for (outcome in results) {
                            if (outcome.error != null) {
                                restartPaths.add(outcome.path)
                                write("Could not update ${Path.of(outcome.path).fileName}: ${message(outcome.error)}\n", true)
                                continue
                            }
                            val result = outcome.result!!
                            if (outcome.path == context?.path) {
                                currentNames = result.getAsJsonArray("names").map { it.asString }.toSet()
                            }
                            val index = additions.indexOfFirst { it is Addition.File && it.path == outcome.path }
                            if (index >= 0) {
                                val bindings = result.getAsJsonObject("file").getAsJsonArray("bindings").map {
                                    FileBinding(it.asJsonObject.get("exported").asString, it.asJsonObject.get("local").asString)
                                }
                                additions[index] = Addition.File(outcome.path, bindings)
                            }
                            stalePaths.remove(outcome.path)
                            editedPaths.remove(outcome.path)
                            restartPaths.remove(outcome.path)
                        }
                        refreshAddedNames()
                        for ((path, version) in versions) {
                            if (modifiedVersions[path] == version) modifiedVersions.remove(path)
                        }
                        refreshing = false
                        updateContextStatus()
                        if (modifiedVersions.any { (path, version) -> versions[path] != version }) {
                            refreshContextIfNeeded()
                        }
                    }
                    backend
                }
            }
        }, background)
        // A failed refresh must not poison later commands: they keep using the previous runtime state.
        contextReady = next.exceptionallyCompose { previous }
        next.whenComplete { _, error -> if (error != null) onEdt(epoch) {
            refreshing = false
            restartPaths.addAll(versions.keys)
            updateContextStatus()
            write("Could not refresh context: ${message(error)}\n", true)
        } }
    }

    /**
     * Top-level declarations of a context file. The runtime exposes the
     * unexported ones, like DevTools shows a script's top-level functions.
     */
    private fun declarationFields(path: String?): Map<String, Any?> {
        val file = path?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return emptyMap()
        val graph = TopLevelDeclarations.collect(project, file)
        return mapOf("declared" to graph[file.path].orEmpty(), "dependencies" to graph - file.path)
    }

    private fun reportHidden(name: String?, result: JsonObject) {
        val hidden = result.getAsJsonArray("hidden")?.map { it.asString }.orEmpty()
        if (hidden.isNotEmpty()) write("Unavailable until Restart Runtime (another module loaded ${name ?: "the file"} before the console could expose them): " +
            hidden.joinToString() + "\n")
    }

    private fun hasEnabledJavaScriptBreakpoint(path: String): Boolean =
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .any { it.type.id == "javascript" && it.isEnabled &&
                VirtualFileManager.getInstance().findFileByUrl(it.fileUrl)?.path == path }
    private data class ReloadResult(val path: String, val result: JsonObject?, val error: Throwable?)

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').let { if (System.getProperty("os.name").startsWith("Windows")) it.lowercase() else it }

    fun addSymbol(file: VirtualFile, exported: String, local: String? = null) {
        if (!file.isValid || !file.isInLocalFileSystem) return
        if (ready == null) restart()
        val epoch = generation
        val saved = saveContext(file, epoch)
        val backend = contextReady ?: return
        backend.thenCombine(saved) { process, _ -> process }
            .thenComposeAsync({ process ->
                process.request("add_symbol", mapOf("path" to file.path, "imported" to exported, "local" to local) + declarationFields(file.path))
            }, background)
            .whenComplete { result, error -> onEdt(epoch) {
                if (error != null) write("Could not add $exported: ${message(error)}\n", true)
                else {
                    val local = result.get("local").asString
                    val addition = Addition.Symbol(file.path, exported, local)
                    if (addition !in additions) {
                        additions.add(addition)
                    }
                    addedNames = addedNames + local
                    refreshCompletionNames()
                    write("Added ${file.name}: $exported as $local\n")
                }
            } }
    }

    fun isFileAdded(file: VirtualFile): Boolean = file.path in addedFilePaths

    fun addFile(file: VirtualFile) {
        if (!file.isValid || !file.isInLocalFileSystem) return
        if (isFileAdded(file)) return
        if (ready == null) restart()
        val epoch = generation
        val saved = saveContext(file, epoch)
        val backend = contextReady ?: return
        backend.thenCombine(saved) { process, _ -> process }
            .thenComposeAsync({ process ->
                process.request("add_file", mapOf("path" to file.path) + declarationFields(file.path))
            }, background)
            .whenComplete { result, error -> onEdt(epoch) {
                if (error != null) write("Could not add ${file.name}: ${message(error)}\n", true)
                else {
                    val bindings = result.getAsJsonArray("bindings").map { FileBinding(it.asJsonObject.get("exported").asString, it.asJsonObject.get("local").asString) }
                    val unsupported = result.getAsJsonArray("unsupported").map { it.asString }
                    reportHidden(file.name, result)
                    if (additions.none { it is Addition.File && it.path == file.path }) {
                        additions.add(Addition.File(file.path, bindings))
                    }
                    addedFilePaths = addedFilePaths + file.path
                    knownContextPaths.add(file.path)
                    addedNames = addedNames + bindings.map { it.local }
                    refreshCompletionNames()
                    updateContextStatus()
                    val aliases = bindings.filter { it.exported != it.local }.joinToString { "${it.exported} → ${it.local}" }
                    write("Added file ${file.name}: ${bindings.size} exports" +
                        (if (aliases.isEmpty()) "" else "; aliases: $aliases") +
                        (if (unsupported.isEmpty()) "" else "; unsupported: ${unsupported.joinToString()}") + "\n")
                }
            } }
    }

    fun removeFile(file: VirtualFile) {
        if (!isFileAdded(file)) return
        val epoch = generation
        val backend = contextReady ?: return
        backend.thenComposeAsync({ process -> process.request("remove_file", mapOf("path" to file.path)) }, background)
            .whenComplete { _, error -> onEdt(epoch) {
                if (error != null) write("Could not remove ${file.name}: ${message(error)}\n", true)
                else {
                    additions.removeAll { it is Addition.File && it.path == file.path }
                    stalePaths.remove(file.path)
                    restartPaths.remove(file.path)
                    modifiedVersions.remove(file.path)
                    addedFilePaths = addedFilePaths - file.path
                    refreshAddedNames()
                    write("Removed file ${file.name} from Bun Console context\n")
                    loadContext(backend, epoch)
                }
            } }
    }

    private fun followEditor() {
        val next = activeFile()
        ConsoleTrace.log("EDITOR", FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path.toString() +
            if (next == context) " (context unchanged)" else " -> context ${next?.path ?: "plain JavaScript"}")
        if (next == context) return
        context = next
        loadContext(contextReady ?: ready ?: return, generation)
    }

    private fun loadContext(startup: CompletableFuture<BunConsoleProcess>, epoch: Long) {
        val file = context
        val toRestore = additions.toList()
        val saved = saveContext(file, epoch)
        updateStatus("Loading · ${file?.name ?: "plain JavaScript"}")
        contextReady = startup.thenCombine(saved) { backend, _ -> backend }.thenComposeAsync({ backend ->
            backend.request("load", mapOf("path" to file?.path) + declarationFields(file?.path)).handle { result, error ->
                onEdt(epoch) {
                    if (error != null) {
                        write("Could not load ${file?.name}: ${message(error)}\n", true)
                        updateStatus("Context load failed · ${file?.name ?: "no file"}; previous context retained")
                    } else {
                        val collisions = result.getAsJsonArray("collisions").map { it.asString }
                        val count = result.getAsJsonArray("names").size()
                        currentNames = result.getAsJsonArray("names").map { it.asString }.toSet()
                        addedNames = additions.filterIsInstance<Addition.Symbol>().map(Addition.Symbol::local).toSet()
                        file?.path?.let(knownContextPaths::add)
                        refreshCompletionNames()
                        val skipped = result.getAsJsonArray("unsupported").map { it.asString }
                        reportHidden(file?.name, result)
                        write("Loaded ${file?.name ?: "plain JavaScript"}: $count exports" +
                            (if (collisions.isEmpty()) "" else "; collisions: ${collisions.joinToString()}") +
                            (if (skipped.isEmpty()) "" else "; names requiring an alias: ${skipped.joinToString()}") + "\n")
                        updateContextStatus()
                    }
                }
                backend
            }
        }, background).thenComposeAsync({ backend ->
            var restored = CompletableFuture.completedFuture(backend)
            for (addition in toRestore) {
                restored = restored.thenCompose { process ->
                    val request = when (addition) {
                        is Addition.Symbol -> process.request("add_symbol", mapOf("path" to addition.path, "imported" to addition.exported,
                            "local" to addition.local) + declarationFields(addition.path))
                        is Addition.File -> process.request("add_file", mapOf("path" to addition.path, "bindings" to addition.bindings) + declarationFields(addition.path))
                    }
                    request.handle { result, error ->
                            onEdt(epoch) {
                                if (error != null) write("Could not restore ${when (addition) { is Addition.Symbol -> addition.local; is Addition.File -> addition.path }}: ${message(error)}\n", true)
                                else {
                                    if (addition is Addition.File) {
                                            val actual = result.getAsJsonArray("bindings").map {
                                                FileBinding(it.asJsonObject.get("exported").asString, it.asJsonObject.get("local").asString)
                                            }
                                            val index = additions.indexOf(addition)
                                            if (index >= 0) additions[index] = addition.copy(bindings = actual)
                                    }
                                    refreshAddedNames()
                                }
                            }
                            process
                        }
                }
            }
            restored
        }, background).whenComplete { _, error ->
            if (error != null) onEdt(epoch) { updateStatus("Failed · ${message(error)}"); write("${message(error)}\n", true) }
        }
    }

    private fun activeFile(): VirtualFile? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.takeIf(ConsoleFiles::isSource)

    private fun saveContext(file: VirtualFile?, epoch: Long): CompletableFuture<Void> {
        val saved = CompletableFuture<Void>()
        ApplicationManager.getApplication().invokeLater({
            try {
                check(!disposed && !project.isDisposed && epoch == generation) { "Context save superseded" }
                val manager = FileDocumentManager.getInstance()
                file?.takeIf { it.isValid }?.let { manager.getCachedDocument(it) }?.let { document ->
                    if (manager.isDocumentUnsaved(document)) manager.saveDocument(document)
                    check(!manager.isDocumentUnsaved(document)) { "Could not save ${file.name}; context was not loaded" }
                }
                saved.complete(null)
            } catch (error: Exception) { saved.completeExceptionally(error) }
        }, ModalityState.nonModal())
        return saved
    }

    private var tracedTabs = ""
    private fun updateStatus(value: String) {
        if (value != status) ConsoleTrace.log("STATUS", value)
        val tabs = contextTabs().joinToString(" | ") { (if (it.active) "*" else "") + it.label }
        if (tabs != tracedTabs) { tracedTabs = tabs; ConsoleTrace.log("TABS", tabs) }
        status = value
        statusChanged?.invoke(value)
    }
    private fun updateContextStatus() = updateStatus(contextStatusText())
    private fun contextStatusText(): String {
        val restart = restartPaths.firstOrNull()
        val changed = stalePaths.firstOrNull { it == context?.path || it in addedFilePaths }
        val elapsed = running.values.firstOrNull()?.let { System.nanoTime() - it } ?: 0L
        val seconds = TimeUnit.NANOSECONDS.toSeconds(elapsed)
        val paused = pausedLabel()
        return (contextTabs().firstOrNull { it.active }?.label ?: "JavaScript") +
            when {
                blocked -> " · JavaScript is busy ($seconds s); Restart Runtime to stop it"
                paused.isNotEmpty() -> "$paused; input evaluates in this frame"
                !debugPaused && elapsed >= SHOW_RUNNING_AFTER -> " · running…" + if (seconds > 0) " $seconds s" else ""
                restart != null -> " · ${Path.of(restart).fileName} changed; Restart Runtime to update dependencies"
                changed != null -> " · ${Path.of(changed).fileName} changed; updates when console is focused"
                else -> ""
            }
    }
    private fun refreshCompletionNames() { contextNames = currentNames + addedNames + consoleNames }
    private fun refreshAddedNames() {
        addedNames = additions.flatMap {
            when (it) {
                is Addition.Symbol -> listOf(it.local)
                is Addition.File -> it.bindings.map(FileBinding::local)
            }
        }.toSet()
        refreshCompletionNames()
    }
    private fun write(text: String, error: Boolean = false) {
        writeEntry(text, if (error) OutputKind.ERROR else OutputKind.NORMAL)
    }
    private fun writeEntry(text: String, kind: OutputKind, styles: List<ConsoleStyle> = emptyList()) {
        if (text.isNotBlank() || kind == OutputKind.CLEAR) ConsoleTrace.log("OUT:" + kind.name.take(5), ConsoleTrace.clip(text))
        entries.add(Entry(text, kind, styles.toList()))
        if (entries.size > 2000) entries.removeAt(0)
        append?.invoke(text, kind, styles)
    }
    private fun message(error: Throwable): String =
        if (error is CompletionException && error.cause != null) message(error.cause!!) else error.message ?: error.toString()

    private fun onEdt(epoch: Long, stale: () -> Unit = {}, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (disposed || project.isDisposed || epoch != generation) stale() else action()
        }, ModalityState.nonModal())
    }

    override fun dispose() {
        disposed = true
        stop()
        busyTimer = null
        background.shutdownNow()
        append = null
        statusChanged = null
    }
}

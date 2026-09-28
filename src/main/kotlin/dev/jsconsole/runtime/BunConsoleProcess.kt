package dev.jsconsole.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BunConsoleProcess(
    private val executable: String,
    private val bootstrap: Path,
    private val workingDirectory: Path,
    private val output: (String, Boolean) -> Unit,
    private val stopped: (String) -> Unit,
    private val debuggerAttach: ((String) -> CompletableFuture<Unit>)? = null,
) : AutoCloseable {
    private val gson = Gson()
    private val closed = AtomicBoolean()
    private val sequence = AtomicLong()
    private val jobs = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    val termination = CompletableFuture<Void>()
    private val io = Executors.newCachedThreadPool { task -> Thread(task, "JS Console I/O").apply { isDaemon = true } }
    @Volatile private var process: Process? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null

    fun start(): CompletableFuture<BunConsoleProcess> = CompletableFuture.supplyAsync({
        try {
        check(!closed.get()) { "Console stopped" }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        listener = server
        server.soTimeout = if (debuggerAttach == null) 15_000 else 45_000
        val token = UUID.randomUUID().toString()
        val endpoint = InspectorEndpoint(UUID.randomUUID().toString())
        val arguments = mutableListOf(executable)
        if (debuggerAttach != null) arguments.add("--inspect-wait=127.0.0.1:0/${endpoint.path}")
        arguments.add(bootstrap.toString())
        val builder = ProcessBuilder(arguments).directory(workingDirectory.toFile())
        builder.environment()["JS_CONSOLE_PORT"] = server.localPort.toString()
        builder.environment()["JS_CONSOLE_TOKEN"] = token
        builder.environment()["NO_COLOR"] = "1"
        val child = builder.start()
        process = child
        child.onExit().thenRun { termination.complete(null); fail("Bun exited (code ${child.exitValue()})") }
        if (closed.get()) { child.destroyForcibly(); server.close(); error("Console stopped") }
        child.outputStream.close()
        for ((stream, error) in listOf(child.inputStream to false, child.errorStream to true)) {
            io.execute {
                runCatching {
                    stream.reader(Charsets.UTF_8).use { reader ->
                        if (error && debuggerAttach != null) {
                            var inspectorBanner = false
                            reader.buffered().forEachLine { line ->
                                endpoint.accept("$line\n")?.let { url ->
                                    try {
                                        debuggerAttach.invoke(url).whenComplete { _, failure ->
                                            if (failure != null) fail("Debugger could not attach: ${failure.message}")
                                        }
                                    } catch (failure: Exception) { fail("Debugger could not attach: ${failure.message}") }
                                }
                                val delimiter = line.contains("Bun Inspector")
                                val informational = inspectorBanner || delimiter
                                if (delimiter) inspectorBanner = !inspectorBanner
                                output("$line\n", !informational)
                            }
                        } else {
                            val buffer = CharArray(4096)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                output(String(buffer, 0, count), error)
                            }
                        }
                    }
                }
            }
        }
        try {
            val connection = server.accept()
            socket = connection
            connection.soTimeout = 15_000
            val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
            val hello = JsonParser.parseString(reader.readLine() ?: error("Bun closed during startup")).asJsonObject
            check(hello.get("event")?.asString == "ready" && hello.get("token")?.asString == token) { "Invalid Bun handshake" }
            connection.soTimeout = 0
            writer = connection.getOutputStream().bufferedWriter(Charsets.UTF_8)
            check(!closed.get()) { "Console stopped" }
            io.execute {
                try {
                    while (!closed.get()) {
                        val line = reader.readLine() ?: break
                        val message = JsonParser.parseString(line).asJsonObject
                        val id = message.get("id")?.asLong ?: continue
                        val job = jobs.remove(id) ?: continue
                        if (message.get("ok")?.asBoolean == true) job.complete(message)
                        else job.completeExceptionally(IllegalStateException(message.get("error")?.asString ?: "Evaluation failed"))
                    }
                    fail("Bun connection closed")
                } catch (error: Exception) { fail(error.message ?: "Bun connection failed") }
            }
            output("Bun ${hello.get("version")?.asString} ready\n", false)
            this
        } catch (error: Exception) {
            close()
            throw error
        } finally { server.close() }
        } catch (error: Exception) { close(); throw error }
    }, io)

    @Synchronized
    fun request(operation: String, fields: Map<String, Any?> = emptyMap()): CompletableFuture<JsonObject> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Console stopped"))
        if (jobs.size >= 128) return CompletableFuture.failedFuture(IllegalStateException("Too many queued commands; restart if code is stuck"))
        val id = sequence.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        jobs[id] = future
        try {
            val message = gson.toJson(fields + mapOf("id" to id, "op" to operation))
            require(message.length <= 1_000_000) { "Command exceeds 1 MB" }
            val sink = writer ?: error("Bun is not ready")
            sink.write(message)
            sink.newLine()
            sink.flush()
        } catch (error: Exception) { jobs.remove(id); future.completeExceptionally(error) }
        return future
    }

    private fun fail(reason: String) {
        if (!closed.get()) { close(); stopped(reason) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching { listener?.close() }
        // Processes started by console code belong to this runtime. Collect them
        // before Bun exits: orphans are no longer its descendants.
        process?.let { child ->
            val started = runCatching { child.descendants().toList() }.getOrDefault(emptyList())
            if (child.isAlive) child.destroyForcibly()
            started.forEach { runCatching { it.destroyForcibly() } }
        }
        if (process == null) termination.complete(null)
        jobs.values.forEach { it.completeExceptionally(IllegalStateException("Runtime restarted or stopped")) }
        jobs.clear()
        io.shutdown()
    }
}

object BunRuntimeLocator {
    fun locate(explicit: String?): String {
        if (!explicit.isNullOrBlank()) {
            val path = Path.of(explicit)
            require(path.isAbsolute) { "Use an absolute path to the Bun executable." }
            require(Files.isRegularFile(path) && Files.isExecutable(path)) { "Bun executable not found or not executable: $explicit" }
            return explicit
        }
        // The IDE's PATH lookup also sees the login-shell PATH on macOS/Linux and PATHEXT on Windows.
        PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("bun")?.toPath()
            ?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.let { return it.toString() }
        val binary = if (SystemInfo.isWindows) "bun.exe" else "bun"
        val home = Path.of(System.getProperty("user.home"))
        val candidates = listOfNotNull(
            System.getenv("BUN_INSTALL")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "bin", binary) },
            home.resolve(".bun").resolve("bin").resolve(binary),
            Path.of("/opt/homebrew/bin/bun").takeIf { SystemInfo.isMac },
            Path.of("/usr/local/bin/bun").takeUnless { SystemInfo.isWindows },
        )
        return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()
            ?: error("Bun was not found. Install Bun 1.4+ or select its executable in Settings > Tools > JS Console.")
    }
}

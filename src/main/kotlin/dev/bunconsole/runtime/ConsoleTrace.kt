package dev.bunconsole.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Development trace of what the console shows and does: input, output, status,
 * tabs, editor switches and runtime traffic. Enabled only by the system property
 * `bun.console.trace=<file>`, which the Gradle `runIde` task sets.
 */
object ConsoleTrace {
    private val file: Path? = System.getProperty("bun.console.trace")?.takeIf { it.isNotBlank() }?.let(Path::of)
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var started = false

    @Synchronized
    fun log(kind: String, text: String) {
        val target = file ?: return
        runCatching {
            Files.createDirectories(target.parent)
            val header = if (started) "" else "\n===== ${java.time.LocalDateTime.now()} IDE session =====\n".also { started = true }
            val body = text.trimEnd('\n').replace("\n", "\n" + " ".repeat(22))
            Files.writeString(target, "$header${LocalTime.now().format(clock)} ${kind.padEnd(9)} $body\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    fun clip(text: String, limit: Int = 2000): String = if (text.length <= limit) text else text.take(limit) + "… (${text.length} chars)"
}

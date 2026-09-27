package dev.jsconsole.runtime

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object BootstrapManager {
    @Synchronized
    fun materialize(): Path {
        val bytes = javaClass.getResourceAsStream("/runtime/bootstrap.mjs")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val directory = Path.of(PathManager.getSystemPath(), "js-console", hash)
        Files.createDirectories(directory)
        val script = directory.resolve("bootstrap.mjs")
        if (!Files.exists(script) || !Files.readAllBytes(script).contentEquals(bytes)) Files.write(script, bytes)
        return script
    }
}

package dev.bunconsole.runtime

import com.intellij.openapi.vfs.VirtualFile

/** Files Bun can load directly as a console context. */
object ConsoleFiles {
    private val EXTENSIONS = setOf("js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx")

    fun isSource(file: VirtualFile?): Boolean =
        file != null && file.isInLocalFileSystem && file.extension?.lowercase() in EXTENSIONS

    /** The compiler that code selected in [file] needs before it runs as console input, or null for JavaScript. */
    fun loader(file: VirtualFile?): String? = when (file?.extension?.lowercase()) {
        "ts", "mts", "cts" -> "ts"
        "tsx" -> "tsx"
        "jsx" -> "jsx"
        else -> null
    }
}

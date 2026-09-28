package dev.jsconsole.runtime

import com.intellij.openapi.vfs.VirtualFile

/** Files Bun can load directly as a console context. */
object ConsoleFiles {
    private val EXTENSIONS = setOf("js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx")

    fun isSource(file: VirtualFile?): Boolean =
        file != null && file.isInLocalFileSystem && file.extension?.lowercase() in EXTENSIONS
}

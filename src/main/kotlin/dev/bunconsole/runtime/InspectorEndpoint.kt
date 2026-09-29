package dev.bunconsole.runtime

/** Reads only Bun's startup announcement, never the inspector protocol or REPL output. */
class InspectorEndpoint(val path: String) {
    private val pending = StringBuilder()
    private var found = false
    fun accept(chunk: String): String? {
        if (found) return null
        pending.append(chunk)
        val match = Regex("ws://127\\.0\\.0\\.1:([0-9]+)/${Regex.escape(path)}(?=\\s)").find(pending)
        if (match != null) {
            val port = match.groupValues[1].toIntOrNull()
            require(port != null && port in 1..65535) { "Invalid Bun inspector port" }
            val url = match.value
            found = true
            pending.clear()
            return url
        }
        if (pending.length > 8192) pending.delete(0, pending.length - 8192)
        return null
    }
}

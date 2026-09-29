package dev.bunconsole

import dev.bunconsole.runtime.BunConsoleProcess
import dev.bunconsole.runtime.BunRuntimeLocator
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BunConsoleProcessTest {
    @Test fun evaluatesAndCanStopAnInfiniteExpression() {
        val directory = Files.createTempDirectory("bun-console-process-test")
        val bootstrap = directory.resolve("bootstrap.mjs")
        javaClass.getResourceAsStream("/runtime/bootstrap.mjs")!!.use { Files.copy(it, bootstrap) }
        val runtime = BunConsoleProcess(BunRuntimeLocator.locate(null), bootstrap, directory, { _, _ -> }, {})
        try {
            runtime.start().get(20, TimeUnit.SECONDS)
            val response = runtime.request("eval", mapOf("code" to "[1, 2, 3].reduce((a,b) => a+b, 0)")).get(5, TimeUnit.SECONDS)
            assertEquals("6", response.get("text").asString)
            val endless = runtime.request("eval", mapOf("code" to "while (true) {}"))
            runtime.close()
            assertFails { endless.get(5, TimeUnit.SECONDS) }
        } finally {
            runtime.close()
            // Windows keeps the child's working directory locked until it exits.
            runtime.termination.get(5, TimeUnit.SECONDS)
            Files.deleteIfExists(bootstrap)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun closingTheRuntimeEndsProcessesStartedByConsoleCode() {
        val directory = Files.createTempDirectory("bun-console-children-test")
        val bootstrap = directory.resolve("bootstrap.mjs")
        javaClass.getResourceAsStream("/runtime/bootstrap.mjs")!!.use { Files.copy(it, bootstrap) }
        val runtime = BunConsoleProcess(BunRuntimeLocator.locate(null), bootstrap, directory, { _, _ -> }, {})
        try {
            runtime.start().get(20, TimeUnit.SECONDS)
            val pid = runtime.request("eval", mapOf("code" to "Bun.spawn([process.execPath, '-e', 'setInterval(() => {}, 1000)']).pid"))
                .get(5, TimeUnit.SECONDS).get("text").asString.toLong()
            val child = ProcessHandle.of(pid).orElseThrow()
            assertTrue(child.isAlive)
            runtime.close()
            runtime.termination.get(5, TimeUnit.SECONDS)
            child.onExit().get(5, TimeUnit.SECONDS)
            assertFalse(child.isAlive)
        } finally {
            runtime.close()
            runtime.termination.get(5, TimeUnit.SECONDS)
            Files.deleteIfExists(bootstrap)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun missingExecutableFailsStartupAndCanBeClosed() {
        val runtime = BunConsoleProcess("Z:/missing-bun-console-bun.exe", Path.of("missing.mjs"), Path.of(System.getProperty("user.dir")), { _, _ -> }, {})
        try { assertFails { runtime.start().get(5, TimeUnit.SECONDS) } }
        finally { runtime.close() }
    }

    @Test fun addsOnlyExportsAndPreservesExistingNames() {
        val directory = Files.createTempDirectory("bun-console-symbol-test")
        val bootstrap = directory.resolve("bootstrap.mjs")
        val module = directory.resolve("a.ts")
        javaClass.getResourceAsStream("/runtime/bootstrap.mjs")!!.use { Files.copy(it, bootstrap) }
        Files.writeString(module, "export let lexical = 3; export function increment() { lexical++ }; export const process = 7; export default function defaultFn() { return 13 }; const privateValue = 99")
        val runtime = BunConsoleProcess(BunRuntimeLocator.locate(null), bootstrap, directory, { _, _ -> }, {})
        try {
            runtime.start().get(20, TimeUnit.SECONDS)
            val loaded = runtime.request("load", mapOf("path" to module.toString())).get(5, TimeUnit.SECONDS)
            assertTrue(loaded.getAsJsonArray("collisions").map { it.asString }.contains("process"))
            val local = runtime.request("add_symbol", mapOf("path" to module.toString(), "imported" to "process")).get(5, TimeUnit.SECONDS)
            assertEquals("process_2", local.get("local").asString)
            assertEquals("7", runtime.request("eval", mapOf("code" to "process_2")).get(5, TimeUnit.SECONDS).get("text").asString)
            runtime.request("add_symbol", mapOf("path" to module.toString(), "imported" to "default", "local" to "defaultFn")).get(5, TimeUnit.SECONDS)
            assertEquals("13", runtime.request("eval", mapOf("code" to "defaultFn()")).get(5, TimeUnit.SECONDS).get("text").asString)
            assertEquals("4", runtime.request("eval", mapOf("code" to "increment(); lexical")).get(5, TimeUnit.SECONDS).get("text").asString)
            val pinned = runtime.request("add_symbol", mapOf("path" to module.toString(), "imported" to "lexical")).get(5, TimeUnit.SECONDS)
            assertEquals("lexical", pinned.get("local").asString)
            runtime.request("load", mapOf("path" to null)).get(5, TimeUnit.SECONDS)
            assertEquals("4", runtime.request("eval", mapOf("code" to "lexical")).get(5, TimeUnit.SECONDS).get("text").asString)
            assertFails { runtime.request("add_symbol", mapOf("path" to module.toString(), "imported" to "privateValue")).get(5, TimeUnit.SECONDS) }
        } finally {
            runtime.close()
            runtime.termination.get(5, TimeUnit.SECONDS)
            Files.deleteIfExists(bootstrap)
            Files.deleteIfExists(module)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun addedFilesCoexistWithFollowingContextAndCanBeRemoved() {
        val directory = Files.createTempDirectory("bun-console-files-test")
        val bootstrap = directory.resolve("bootstrap.mjs")
        val a = directory.resolve("a.ts")
        val b = directory.resolve("b.ts")
        val current = directory.resolve("current.ts")
        javaClass.getResourceAsStream("/runtime/bootstrap.mjs")!!.use { Files.copy(it, bootstrap) }
        Files.writeString(a, "export let shared = 1; export function bump() { shared++ }; export const alpha = 10; export default 42; const secret = 77")
        Files.writeString(b, "export const shared = 20; export const beta = 30")
        Files.writeString(current, "export const current = 5; export const shared = 100")
        val runtime = BunConsoleProcess(BunRuntimeLocator.locate(null), bootstrap, directory, { _, _ -> }, {})
        try {
            runtime.start().get(20, TimeUnit.SECONDS)
            runtime.request("load", mapOf("path" to current.toString())).get(5, TimeUnit.SECONDS)
            val first = runtime.request("add_file", mapOf("path" to a.toString())).get(5, TimeUnit.SECONDS)
            assertTrue(first.getAsJsonArray("bindings").any { it.asJsonObject.get("local").asString == "shared_2" })
            assertTrue(first.getAsJsonArray("bindings").any { it.asJsonObject.get("local").asString == "a_default" })
            val second = runtime.request("add_file", mapOf("path" to b.toString())).get(5, TimeUnit.SECONDS)
            assertTrue(second.getAsJsonArray("bindings").any { it.asJsonObject.get("local").asString == "shared_3" })
            assertEquals("'100,1,20,10,30,42'", runtime.request("eval", mapOf("code" to "[shared,shared_2,shared_3,alpha,beta,a_default].join(',')")).get(5, TimeUnit.SECONDS).get("text").asString)
            assertEquals("2", runtime.request("eval", mapOf("code" to "bump(); shared_2")).get(5, TimeUnit.SECONDS).get("text").asString)
            runtime.request("load", mapOf("path" to null)).get(5, TimeUnit.SECONDS)
            runtime.request("add_file", mapOf("path" to a.toString(), "bindings" to first.getAsJsonArray("bindings"))).get(5, TimeUnit.SECONDS)
            runtime.request("add_file", mapOf("path" to b.toString(), "bindings" to second.getAsJsonArray("bindings"))).get(5, TimeUnit.SECONDS)
            assertEquals("'undefined,2,20,undefined'", runtime.request("eval", mapOf("code" to "[typeof current,shared_2,shared_3,typeof secret].join(',')")).get(5, TimeUnit.SECONDS).get("text").asString)
            runtime.request("remove_file", mapOf("path" to a.toString())).get(5, TimeUnit.SECONDS)
            assertEquals("'undefined,30'", runtime.request("eval", mapOf("code" to "[typeof shared_2,beta].join(',')")).get(5, TimeUnit.SECONDS).get("text").asString)
            val restored = runtime.request("add_file", mapOf("path" to a.toString(), "bindings" to first.getAsJsonArray("bindings"))).get(5, TimeUnit.SECONDS)
            assertTrue(restored.getAsJsonArray("bindings").any { it.asJsonObject.get("local").asString == "shared_2" })
        } finally {
            runtime.close()
            runtime.termination.get(5, TimeUnit.SECONDS)
            for (path in listOf(bootstrap, a, b, current)) Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}

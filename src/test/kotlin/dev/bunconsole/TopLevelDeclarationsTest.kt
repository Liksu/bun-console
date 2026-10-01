package dev.bunconsole

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bunconsole.runtime.TopLevelDeclarations
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The console collects declarations on its own background threads, which have
 * no progress indicator. Resolving imports in a TypeScript project (tsconfig.json)
 * needs one; this reproduces the "There is no ProgressIndicator or Job" report.
 */
class TopLevelDeclarationsTest : BasePlatformTestCase() {
    fun testResolverWaitsWorkOnTheConsoleThreads() {
        // What the console did before: a plain read action on a plain thread. The JS resolver's
        // runBlockingCancellable fails there exactly as in the user's report.
        val plain = onPlainThread {
            ApplicationManager.getApplication().runReadAction<Int> { runBlockingCancellable { 1 } }
        }
        val failure = runCatching { plain.get(1, TimeUnit.SECONDS) }.exceptionOrNull()?.cause
        assertTrue("Unexpected: $failure", failure?.message?.contains("There is no ProgressIndicator or Job") == true)
        val cancellable = onPlainThread { TopLevelDeclarations.cancellableRead(project) { runBlockingCancellable { 42 } } }
        assertEquals(42, cancellable.get(1, TimeUnit.SECONDS))
    }

    private fun <T> onPlainThread(action: () -> T): java.util.concurrent.CompletableFuture<T> {
        val future = java.util.concurrent.CompletableFuture<T>()
        Thread {
            try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) }
        }.start()
        PlatformTestUtil.waitWithEventsDispatching("Thread did not finish", { future.isDone }, 30)
        return future
    }

    fun testImportGraphIsCollectedFromAPlainBackgroundThread() {
        val directory = Files.createTempDirectory("bun-console-declarations-graph")
        Files.writeString(directory.resolve("tsconfig.json"), """{ "compilerOptions": { "module": "esnext", "strict": true } }""")
        Files.writeString(directory.resolve("store.ts"), "const items: string[] = [];\nexport function add(item: string) { return items.push(item); }")
        Files.writeString(directory.resolve("uses.ts"),
            "import { add } from './store';\nimport { pad } from 'left-pad';\nexport function useStore() { return pad(String(add('apple'))); }")
        // Module augmentations like a project's env.d.ts send import resolution through the tsconfig import graph.
        Files.writeString(directory.resolve("env.d.ts"), "declare module 'left-pad' { export function pad(text: string): string }\n" +
            "declare module './store' { export const extra: number }")
        Files.createDirectories(directory.resolve("node_modules/left-pad"))
        Files.writeString(directory.resolve("node_modules/left-pad/package.json"), """{ "name": "left-pad", "version": "1.0.0", "main": "index.js", "types": "index.d.ts" }""")
        Files.writeString(directory.resolve("node_modules/left-pad/index.js"), "exports.pad = (text) => text;")
        Files.writeString(directory.resolve("node_modules/left-pad/index.d.ts"), "export declare function pad(text: string): string;")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
        val uses = root.findChild("uses.ts")!!
        PsiTestUtil.addContentRoot(module, root)
        try {
            // A plain thread, like the console's own executor: no IDE job or progress in its context.
            val future = java.util.concurrent.CompletableFuture<Map<String, List<String>>>()
            Thread {
                try { future.complete(TopLevelDeclarations.collect(project, uses)) }
                catch (error: Throwable) { future.completeExceptionally(error) }
            }.start()
            PlatformTestUtil.waitWithEventsDispatching("Declarations not collected", { future.isDone }, 30)
            val graph = future.get(1, TimeUnit.SECONDS)
            assertEquals(listOf("useStore"), graph[uses.path])
            assertEquals(listOf("items", "add"), graph[root.findChild("store.ts")!!.path])
        } finally {
            PsiTestUtil.removeContentEntry(module, root)
            directory.toFile().deleteRecursively()
        }
    }
}

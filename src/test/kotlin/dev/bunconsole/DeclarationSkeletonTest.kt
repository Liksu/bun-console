package dev.bunconsole

import com.intellij.openapi.application.ReadAction
import dev.bunconsole.runtime.DeclarationSkeleton

/** The syntactic rule: what a file's skeleton keeps without running the file. */
class DeclarationSkeletonTest : ConsoleTestCase() {
    private fun skeleton(name: String, source: String): DeclarationSkeleton.Skeleton {
        val file = myFixture.configureByText(name, source)
        return ReadAction.compute<DeclarationSkeleton.Skeleton, RuntimeException> { DeclarationSkeleton.build(file) }
    }

    /** The skeleton keeps every line and column of the source, then lists its exports. */
    private fun assertSameLayout(source: String, skeleton: DeclarationSkeleton.Skeleton) {
        val body = skeleton.source.substringBefore("\n;export {")
        assertEquals(source.length, body.length)
        assertEquals(source.indices.filter { source[it] == '\n' }, body.indices.filter { body[it] == '\n' })
    }

    fun testDeclarationsStayAndStatementsGo() {
        val source = "var fns = [], a = 0;\n\nfunction build(i) {\n    a++;\n    return () => console.log(a, i);\n}\n\n" +
            "for (var i = 0; i < 10; ++i) fns.push(build(i));\nfns.forEach(fn => fn());\nconsole.log(a + 12)\n"
        val skeleton = skeleton("scratch.js", source)
        assertSameLayout(source, skeleton)
        assertTrue(skeleton.source, skeleton.source.startsWith("var fns = [], a = 0;"))
        assertTrue(skeleton.source, skeleton.source.contains("return () => console.log(a, i);"))
        assertFalse(skeleton.source, skeleton.source.contains("for (") || skeleton.source.contains("forEach") || skeleton.source.contains("a + 12"))
        // `i` is declared by the loop, which does not stay.
        assertEquals(listOf("build", "fns", "a"), skeleton.names)
        assertTrue(skeleton.source, skeleton.source.endsWith(";export { build, fns, a };\n"))
    }

    fun testBuiltInsAreKnownButProjectCodeIsNot() {
        val source = """
            const cache = new Map();
            const limits = { max: Math.max(1, 2), tags: ['a', 'b'].join(','), when: Date.now() };
            const label = `limit ${'$'}{limits.max}`;
            const db = connect();
            let fetched = fetch('https://example.com');
            const doubled = [1, 2].map((x) => x * 2);
            const fromCache = Array.from(cache.keys());
            function connect() { return { connected: true }; }
        """.trimIndent()
        val skeleton = skeleton("store.ts", source)
        assertSameLayout(source, skeleton)
        assertEquals(listOf("connect", "cache", "limits", "label", "fromCache"), skeleton.names)
        // An unknown value is declared without it: `const` becomes `let` so that it can stay uninitialized.
        for (line in listOf("let   db +;", "let fetched +;", "let   doubled +;")) {
            assertTrue(skeleton.source, Regex("(?m)^$line$").containsMatchIn(skeleton.source))
        }
        assertTrue(skeleton.source, skeleton.source.contains("export { connect, cache, limits, label, db, fetched, doubled, fromCache }"))
    }

    fun testAFileCanShadowABuiltIn() {
        val skeleton = skeleton("shadow.js", "function Map() { sideEffect(); }\nconst m = new Map();\nconst n = Math.abs(-1);")
        assertEquals(listOf("Map", "n"), skeleton.names)
    }

    fun testClassesExportsDestructuringAndTypeScript() {
        val source = """
            import { dep } from './dep';
            import type { Shape } from './shape';
            export interface Point { x: number }
            type Id = string;
            export enum Color { Red, Green = Red + 1 }
            export class Box { size: number = dep; static unit = 'px'; area() { return this.size ** 2; } }
            class Loaded { static data = load(); }
            @sealed class Decorated {}
            export const { x, y } = { x: 1, y: 2 };
            const [first] = load();
            export const scaled: number = dep * 2;
            export default function main() { return new Box(); }
            declare const ambient: number;
            main();
        """.trimIndent()
        val skeleton = skeleton("shapes.ts", source)
        assertSameLayout(source, skeleton)
        assertEquals(listOf("main", "Color", "Box", "x", "y", "scaled"), skeleton.names)
        assertTrue(skeleton.source, skeleton.source.contains("import { dep } from './dep';"))
        assertTrue(skeleton.source, skeleton.source.contains("export interface Point { x: number }")) // types go at compile time
        assertTrue(skeleton.source, skeleton.source.contains("       enum Color { Red, Green = Red + 1 }"))
        assertTrue(skeleton.source, skeleton.source.contains("       class Box {"))
        assertTrue(skeleton.source, skeleton.source.contains("let Loaded;"))
        assertTrue(skeleton.source, skeleton.source.contains("let Decorated;"))
        assertTrue(skeleton.source, skeleton.source.contains("               function main()"))
        assertFalse(skeleton.source, skeleton.source.contains("load()") || skeleton.source.contains("ambient") || skeleton.source.contains("main();"))
        assertTrue(skeleton.source, skeleton.source.contains("export { main, Color, Box, Loaded, Decorated, x, y, scaled }"))
    }
}

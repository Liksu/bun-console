package dev.bunconsole

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bunconsole.runtime.ConsoleImports

class ConsoleImportsTest : BasePlatformTestCase() {
    fun testDefaultNamedNamespaceSideEffectAndNoSemicolonImports() {
        val source = """
            import path from 'path'
            import {join as combine, basename} from 'node:path';
            import * as files from 'node:fs';
            import './setup';
            path.join(process.cwd(), 'texts')
        """.trimIndent()
        val result = ConsoleImports.prepare(project, source)
        assertEquals(listOf("path", "node:path", "node:fs", "./setup"), result.imports.map { it.specifier })
        assertEquals(listOf(ConsoleImports.Binding("default", "path")), result.imports[0].bindings)
        assertEquals(listOf(ConsoleImports.Binding("join", "combine"), ConsoleImports.Binding("basename", "basename")), result.imports[1].bindings)
        assertEquals(listOf(ConsoleImports.Binding("*", "files")), result.imports[2].bindings)
        assertTrue(result.imports[3].bindings.isEmpty())
        assertEquals(source.length, result.code.length)
        assertEquals(source.indices.filter { source[it] == '\n' }, result.code.indices.filter { result.code[it] == '\n' })
        assertTrue(result.code.endsWith("path.join(process.cwd(), 'texts')"))
    }

    fun testDynamicImportStringsAndCommentsAreUntouched() {
        val source = "// import fake from 'nope'\nconst s = \"import path from 'path'\"; await import('node:path')"
        val result = ConsoleImports.prepare(project, source)
        assertTrue(result.imports.isEmpty())
        // Comments and strings stay as typed; only the real dynamic import goes through the runtime helper.
        assertEquals(source.replace("const s", "var   s").replace("await import(", "await \$bcImp("), result.code)
    }

    fun testEscapedModuleSpecifierAndDefaultAlias() {
        val result = ConsoleImports.prepare(project, "import {default as paths} from 'node:\\u0070ath'; paths")
        assertEquals("node:path", result.imports.single().specifier)
        assertEquals(listOf(ConsoleImports.Binding("default", "paths")), result.imports.single().bindings)
    }

    fun testTopLevelLexicalDeclarationsAreReusableButNestedDeclarationsStayLexical() {
        val source = """
            const arr = [1, 2]
            let answer = 41
            function read() { const local = arr; return local }
            for (const item of arr) { answer += item }
            arr
        """.trimIndent()
        val prepared = ConsoleImports.prepare(project, source)
        assertEquals(source.replace("const arr", "var   arr").replace("let answer", "var answer"), prepared.code)
        assertEquals(source.length, prepared.code.length)
        assertEquals(source.indices.filter { source[it] == '\n' }, prepared.code.indices.filter { prepared.code[it] == '\n' })
        try {
            ConsoleImports.prepare(project, "const arr = 1; const arr = 2")
            fail("Repeated declarations in one input must remain invalid")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("arr"))
        }
    }

    fun testTopLevelClassesCanBeDeclaredAgain() {
        val prepared = ConsoleImports.prepare(project, "class Point { x = 1 }\n[new Point().x]")
        assertEquals("var Point = class Point { x = 1 };\n[new Point().x]", prepared.code)
        assertEquals(listOf("Point"), prepared.declarations)
        try {
            ConsoleImports.prepare(project, "class A {}; const A = 1")
            fail("A class and a variable with the same name in one input must remain invalid")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("A"))
        }
    }

    fun testDynamicImportsResolveThroughTheRuntimeHelper() {
        val prepared = ConsoleImports.prepare(project, "const m = await import('./b.ts'); import('x').then(String)")
        assertEquals("var   m = await \$bcImp('./b.ts'); \$bcImp('x').then(String)", prepared.code)
        assertTrue(prepared.imports.isEmpty())
    }
}

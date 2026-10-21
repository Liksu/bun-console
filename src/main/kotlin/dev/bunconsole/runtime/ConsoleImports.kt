package dev.bunconsole.runtime

import com.intellij.lang.ecmascript6.psi.ES6ImportCall
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil

/** Split REPL import declarations from executable statements using the JavaScript parser. */
object ConsoleImports {
    data class Binding(val imported: String, val local: String)
    data class Import(val specifier: String, val bindings: List<Binding>)
    data class Evaluation(val code: String, val imports: List<Import>, val declarations: List<String> = emptyList())

    private val TYPE_ONLY_IMPORT = Regex("""^import\s+type\s+(?!from\b)""")
    private val TYPE_ONLY_SPECIFIER = Regex("""^type\s+(?!as\b)""")

    /** [extension] is the language of [source]: "js", or "ts", "tsx" and "jsx" for code selected in such a file. */
    fun prepare(project: Project, source: String, extension: String = "js"): Evaluation {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "bun-console.$extension", FileTypeManager.getInstance().getFileTypeByExtension(extension), source
        )
        val allDeclarations = file.children.filterIsInstance<ES6ImportDeclaration>()
        // TypeScript type imports have nothing to import at runtime; the compiler removes them.
        val typeImports = allDeclarations.filter { TYPE_ONLY_IMPORT.containsMatchIn(it.text) }
        val declarations = allDeclarations - typeImports.toSet()
        val variables = file.children.filterIsInstance<JSVarStatement>()
        // TypeScript interfaces and enums are JSClass too, as are abstract classes; only plain classes become expressions.
        val classes = file.children.filterIsInstance<JSClass>().filter { it.text.startsWith("class") }
        val dynamicImports = PsiTreeUtil.findChildrenOfType(file, ES6ImportCall::class.java)
        if (allDeclarations.isEmpty() && variables.isEmpty() && classes.isEmpty() && dynamicImports.isEmpty()) {
            return Evaluation(source, emptyList())
        }
        val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        require(error == null) { "Invalid ${if (extension.startsWith("ts")) "TypeScript" else "JavaScript"}: ${error?.errorDescription}" }
        val executable = source.toCharArray()
        for (declaration in typeImports) blank(executable, declaration.textRange.startOffset, declaration.textRange.endOffset)
        // `import(…)` becomes the runtime's `$bcImp(…)` (same length, so error positions stay):
        // it resolves relative paths from the context file, as static imports in the console do.
        for (call in dynamicImports) {
            val start = call.textRange.startOffset
            if (source.startsWith("import", start)) "\$bcImp".forEachIndexed { index, char -> executable[start + index] = char }
        }
        val declared = mutableMapOf<String, Boolean>()
        for (statement in variables) {
            val lexical = statement.text.startsWith("const") || statement.text.startsWith("let")
            for (variable in statement.variables) {
                val name = variable.name ?: continue
                if (name in declared && (lexical || declared.getValue(name))) {
                    throw IllegalArgumentException("Identifier '$name' has already been declared in this input")
                }
                declared[name] = lexical
            }
            if (lexical) {
                val start = statement.textRange.startOffset
                val keywordLength = if (source.startsWith("const", start)) 5 else 3
                "var".forEachIndexed { index, char -> executable[start + index] = char }
                for (index in 3 until keywordLength) executable[start + index] = ' '
            }
        }
        // Like DevTools, a console class can be declared again in a later input.
        // The class becomes a `var` holding a class expression; only its own line shifts.
        val insertions = mutableListOf<Pair<Int, String>>()
        for (declaration in classes) {
            val name = declaration.name ?: continue
            if (name in declared) throw IllegalArgumentException("Identifier '$name' has already been declared in this input")
            declared[name] = true
            insertions += declaration.textRange.startOffset to "var $name = "
            insertions += declaration.textRange.endOffset to ";"
        }
        val imports = declarations.map { declaration ->
            require(declaration.withClause == null) { "Import attributes are not supported in Bun Console yet" }
            val moduleText = requireNotNull(declaration.fromClause?.referenceText ?: declaration.importModuleText) {
                "Import must name a module"
            }
            // The import PSI exposes the quoted token. Parse it as a literal to
            // decode JavaScript escapes without treating the user's text as code.
            val literalFile = PsiFileFactory.getInstance(project).createFileFromText(
                "bun-console-module.js", file.fileType, "($moduleText)"
            )
            val specifier = requireNotNull(PsiTreeUtil.findChildOfType(literalFile, JSLiteralExpression::class.java)?.stringValue) {
                "Invalid module specifier"
            }
            val bindings = declaration.importedBindings.map {
                Binding(if (it.isNamespaceImport) "*" else "default", requireNotNull(it.name) { "Missing imported binding name: ${it.text}" })
            } + declaration.importSpecifiers.filterNot { TYPE_ONLY_SPECIFIER.containsMatchIn(it.text) }.map {
                val imported = requireNotNull(it.referenceName) { "Missing specifier name: ${it.text}" }
                Binding(imported, it.alias?.name ?: imported)
            }
            blank(executable, declaration.textRange.startOffset, declaration.textRange.endOffset)
            Import(specifier, bindings)
        }
        val code = StringBuilder(String(executable))
        for ((offset, text) in insertions.sortedByDescending { it.first }) code.insert(offset, text)
        return Evaluation(code.toString(), imports, declared.keys.toList())
    }

    /** Removes an import statement from the executable code. */
    private fun blank(executable: CharArray, start: Int, end: Int) {
        // Keep line/column offsets in runtime errors; the user's source/transcript is untouched.
        for (index in start until end) {
            if (executable[index] != '\n' && executable[index] != '\r') executable[index] = ' '
        }
        // Preserve the statement boundary: removing a middle import must not
        // join the expressions before/after it through automatic semicolons.
        executable[start] = ';'
    }
}

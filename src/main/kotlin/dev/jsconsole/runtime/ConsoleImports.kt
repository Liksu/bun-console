package dev.jsconsole.runtime

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSVarStatement
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

    fun prepare(project: Project, source: String): Evaluation {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "js-console.js", FileTypeManager.getInstance().getFileTypeByExtension("js"), source
        )
        val declarations = file.children.filterIsInstance<ES6ImportDeclaration>()
        val variables = file.children.filterIsInstance<JSVarStatement>()
        if (declarations.isEmpty() && variables.isEmpty()) return Evaluation(source, emptyList())
        val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        require(error == null) { "Invalid JavaScript: ${error?.errorDescription}" }
        val executable = source.toCharArray()
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
        val imports = declarations.map { declaration ->
            require(declaration.withClause == null) { "Import attributes are not supported in JS Console yet" }
            val moduleText = requireNotNull(declaration.fromClause?.referenceText ?: declaration.importModuleText) {
                "Import must name a module"
            }
            // The import PSI exposes the quoted token. Parse it as a literal to
            // decode JavaScript escapes without treating the user's text as code.
            val literalFile = PsiFileFactory.getInstance(project).createFileFromText(
                "js-console-module.js", file.fileType, "($moduleText)"
            )
            val specifier = requireNotNull(PsiTreeUtil.findChildOfType(literalFile, JSLiteralExpression::class.java)?.stringValue) {
                "Invalid module specifier"
            }
            val bindings = declaration.importedBindings.map {
                Binding(if (it.isNamespaceImport) "*" else "default", requireNotNull(it.name) { "Missing imported binding name: ${it.text}" })
            } + declaration.importSpecifiers.map {
                val imported = requireNotNull(it.referenceName) { "Missing specifier name: ${it.text}" }
                Binding(imported, it.alias?.name ?: imported)
            }
            // Keep line/column offsets in runtime errors; the user's source/transcript is untouched.
            for (index in declaration.textRange.startOffset until declaration.textRange.endOffset) {
                if (executable[index] != '\n' && executable[index] != '\r') executable[index] = ' '
            }
            // Preserve the statement boundary: removing a middle import must not
            // join the expressions before/after it through automatic semicolons.
            executable[declaration.textRange.startOffset] = ';'
            Import(specifier, bindings)
        }
        return Evaluation(String(executable), imports, declared.keys.toList())
    }
}

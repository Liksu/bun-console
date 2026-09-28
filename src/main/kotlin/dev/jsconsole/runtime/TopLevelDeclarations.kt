package dev.jsconsole.runtime

import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Names of a module's top-level runtime declarations (functions, classes,
 * variables, enums), exported or not. Type-only and ambient declarations have
 * no runtime value and are skipped. Call inside a read action.
 */
object TopLevelDeclarations {
    fun names(project: Project, file: VirtualFile): List<String> =
        file.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) }?.let(::names).orEmpty()

    /**
     * Declarations of [file] and of the project files it imports, transitively
     * (at most [limit] files). A module loaded as a dependency before the console
     * listed its names could not expose them; listing the import graph up front
     * prepares those modules before any of them runs.
     */
    fun withImports(project: Project, file: VirtualFile, limit: Int = 200): Map<String, List<String>> {
        val index = ProjectFileIndex.getInstance(project)
        val manager = PsiManager.getInstance(project)
        val result = linkedMapOf<String, List<String>>()
        val queue = ArrayDeque(listOf(file))
        while (queue.isNotEmpty() && result.size < limit) {
            val next = queue.removeFirst()
            if (next.path in result || !next.isValid) continue
            val psi = manager.findFile(next) ?: continue
            result[next.path] = names(psi)
            for (declaration in psi.children.filterIsInstance<ES6ImportExportDeclaration>()) {
                for (target in declaration.fromClause?.resolveReferencedElements().orEmpty()) {
                    val source = (target as? PsiFile)?.virtualFile ?: target.containingFile?.virtualFile ?: continue
                    if (ConsoleFiles.isSource(source) && index.isInContent(source) && !index.isInLibrary(source) &&
                        "/node_modules/" !in source.path && source.path !in result) queue.add(source)
                }
            }
        }
        return result
    }

    private fun names(psi: PsiFile): List<String> {
        val names = linkedSetOf<String>()
        for (child in psi.children) {
            if (ambient(child)) continue
            when (child) {
                is JSFunction -> child.name?.let(names::add)
                is JSClass -> child.name?.let(names::add)
                is TypeScriptEnum -> child.name?.let(names::add)
                is JSVarStatement -> PsiTreeUtil.findChildrenOfType(child, JSVariable::class.java)
                    .filter { it !is JSParameter && PsiTreeUtil.getParentOfType(it, JSFunction::class.java, true, JSVarStatement::class.java) == null }
                    .forEach { variable -> variable.name?.let(names::add) }
            }
        }
        return names.toList()
    }

    private fun ambient(element: PsiElement): Boolean =
        (element as? JSAttributeListOwner)?.attributeList?.hasModifier(JSAttributeList.ModifierType.DECLARE) == true
}

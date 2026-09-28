package dev.jsconsole.runtime

import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Names of a module's top-level runtime declarations (functions, classes,
 * variables, enums), exported or not. Type-only and ambient declarations have
 * no runtime value and are skipped. Call inside a read action.
 */
object TopLevelDeclarations {
    fun names(project: Project, file: VirtualFile): List<String> {
        val psi = file.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) } ?: return emptyList()
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

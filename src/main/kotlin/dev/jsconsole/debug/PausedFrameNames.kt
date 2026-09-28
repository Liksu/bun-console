package dev.jsconsole.debug

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xdebugger.XSourcePosition

/**
 * Names in scope at a paused source position, found statically (as the IDE's
 * Evaluate Expression editor does): parameters, locals and inner declarations
 * of every enclosing function, then the file's top level and imports. Block
 * scoping is not modelled; these are completion hints. Call in a read action.
 */
object PausedFrameNames {
    fun at(project: Project, position: XSourcePosition): Set<String> {
        val file = position.file.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) } ?: return emptySet()
        val names = linkedSetOf<String>()
        var scope: PsiElement? = file.findElementAt(position.offset)
        while (scope != null) {
            if (scope is JSFunction || scope is PsiFile) collectOwn(scope, names)
            if (scope is PsiFile) break
            scope = scope.parent
        }
        for (declaration in file.children.filterIsInstance<ES6ImportDeclaration>()) {
            declaration.importedBindings.mapNotNullTo(names) { it.name }
            declaration.importSpecifiers.mapNotNullTo(names) { it.alias?.name ?: it.referenceName }
        }
        return names
    }

    /** Declarations that belong to [scope] itself, not to functions nested in it. */
    private fun collectOwn(scope: PsiElement, names: MutableSet<String>) {
        val owner = scope as? JSFunction
        PsiTreeUtil.findChildrenOfAnyType(scope, JSVariable::class.java, JSFunction::class.java, JSClass::class.java).forEach { element ->
            if (element === scope) return@forEach
            if (PsiTreeUtil.getParentOfType(element, JSFunction::class.java, true) === owner) {
                (element as? com.intellij.psi.PsiNamedElement)?.name?.let(names::add)
            }
        }
    }
}

package dev.bunconsole.ui

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.bunconsole.service.BunConsoleProjectService

/**
 * Names that exist only in the running console (context-file bindings and
 * globals defined by earlier input) are unknown to static analysis; the input
 * must not mark them unresolved. Unknown names are still reported.
 */
class ConsoleInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId != "JSUnresolvedReference") return false
        val file = element.containingFile ?: return false
        if (file.getUserData(ConsoleCompletionContributor.INPUT) != true &&
            file.originalFile.getUserData(ConsoleCompletionContributor.INPUT) != true) return false
        val reference = PsiTreeUtil.getParentOfType(element, JSReferenceExpression::class.java, false) ?: return false
        if (reference.qualifier != null) return false
        val service = file.project.serviceIfCreated<BunConsoleProjectService>() ?: return false
        val name = reference.referenceName ?: return false
        return name in service.contextNames || name in service.pausedFrameNames()
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY
}

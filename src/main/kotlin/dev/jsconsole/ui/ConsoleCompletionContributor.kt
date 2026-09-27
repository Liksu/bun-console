package dev.jsconsole.ui

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import dev.jsconsole.service.JsConsoleProjectService

/** Runtime exports are already bound; accepting them must not trigger an ES module import. */
class ConsoleCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.getUserData(INPUT) != true) return
        val reference = parameters.position.parent as? JSReferenceExpression ?: return
        if (reference.qualifier != null) return
        val names = parameters.originalFile.project.getService(JsConsoleProjectService::class.java).contextNames
        names.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("JS Console", true)) }
        val projectFiles = ProjectFileIndex.getInstance(parameters.originalFile.project)
        result.runRemainingContributors(parameters) { candidate ->
            val item = candidate.lookupElement
            val source = item.psiElement?.containingFile?.virtualFile
            // Project-file suggestions outside the current input may insert an ES import.
            // Such imports are wrong in the REPL, even when the runtime context is stale.
            if (item.lookupString !in names &&
                (source == null || source == parameters.originalFile.virtualFile || !projectFiles.isInContent(source))) {
                result.passResult(candidate)
            }
        }
    }

    companion object {
        val INPUT: Key<Boolean> = Key.create("dev.jsconsole.input")
    }
}

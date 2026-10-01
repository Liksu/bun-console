package dev.bunconsole.ui

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import dev.bunconsole.runtime.ConsoleFiles
import dev.bunconsole.service.BunConsoleProjectService

/** Runtime exports are already bound; accepting them must not trigger an ES module import. */
class ConsoleCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.getUserData(INPUT) != true) return
        val reference = parameters.position.parent as? JSReferenceExpression ?: return
        if (reference.qualifier != null) return
        val service = parameters.originalFile.project.getService(BunConsoleProjectService::class.java)
        val names = service.contextNames + service.pausedFrameNames()
        names.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("Bun Console", true)) }
        val projectFiles = ProjectFileIndex.getInstance(parameters.originalFile.project)
        result.runRemainingContributors(parameters) { candidate ->
            val item = candidate.lookupElement
            val source = item.psiElement?.containingFile?.virtualFile
            // Exports of other source files (in the project or merely opened from elsewhere) would be
            // accepted with an ES import that the runtime does not have, even when its context is stale.
            // Library and built-in declarations (lib.d.ts, @types, packages) stay available.
            val otherSource = source != null && source != parameters.originalFile.virtualFile &&
                (projectFiles.isInContent(source) ||
                    (ConsoleFiles.isSource(source) && !source.name.endsWith(".d.ts") && !projectFiles.isInLibrary(source)))
            if (item.lookupString !in names && !otherSource) result.passResult(candidate)
        }
    }

    companion object {
        val INPUT: Key<Boolean> = Key.create("dev.bunconsole.input")
    }
}

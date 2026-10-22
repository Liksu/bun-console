package dev.bunconsole.ui

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeAlias
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.lang.javascript.psi.JSNamedElement
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import dev.bunconsole.runtime.ConsoleFiles
import dev.bunconsole.service.BunConsoleProjectService

/** Imports a named module export under the caret into the persistent console session. */
class AddSymbolAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val editor = event.getData(CommonDataKeys.EDITOR)
        val file = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isVisible = project != null && editor != null && file != null &&
            ConsoleFiles.isSource(event.getData(CommonDataKeys.VIRTUAL_FILE))
        if (!event.presentation.isVisible) return
        // Without indexes a reference cannot be resolved: the action checks when it runs.
        if (DumbService.isDumb(project!!)) { event.presentation.isEnabled = true; return }
        // Disabled where the console could not provide the name, with the reason in the menu.
        val selection = symbolAt(file!!, editor!!.caretModel.offset)
        event.presentation.isEnabled = selection != null && selection.unavailable == null
        selection?.unavailable?.let { event.presentation.text = "${event.presentation.text} ($it)" }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val virtualFile = file.virtualFile ?: return
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val offset = editor.caretModel.offset
        // Resolving a reference may wait for the JavaScript resolver: do it off the UI thread,
        // in a read action that is cancellable and needs up-to-date indexes.
        ReadAction.nonBlocking<Selection?> { if (file.isValid) symbolAt(file, offset) else null }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { selection -> addSelection(project, virtualFile, selection) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun addSelection(project: Project, virtualFile: VirtualFile, selection: Selection?) {
        if (selection == null) {
            Messages.showInfoMessage(project, "Place the caret on a named top-level declaration or reference.", "Bun Console")
            return
        }
        selection.unavailable?.let { reason ->
            Messages.showInfoMessage(project, "${selection.name}: $reason.", "Bun Console")
            return
        }
        if (!selection.exported && !selection.topLevel) {
            Messages.showInfoMessage(project,
                "${selection.name} is declared inside another declaration. Its live value is available in a paused debugger frame where it is in scope.",
                "Bun Console")
            return
        }
        ToolWindowManager.getInstance(project).getToolWindow("Bun Console")?.show {
            val exported = selection.exportName ?: selection.name
            project.getService(BunConsoleProjectService::class.java).addSymbol(
                virtualFile, exported, if (exported == "default") selection.name else null
            )
        }
    }

    /** [unavailable] tells why the console cannot provide this name. */
    internal data class Selection(val name: String, val exported: Boolean, val exportName: String? = null, val topLevel: Boolean = true,
                                  val unavailable: String? = null)

    internal fun symbolAt(file: PsiFile, offset: Int): Selection? {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        val specifier = PsiTreeUtil.getParentOfType(leaf, ES6ExportSpecifier::class.java, false)
        if (specifier != null) {
            val exported = specifier.alias?.name ?: specifier.referenceName ?: return null
            return Selection(specifier.referenceName ?: exported, true, exported)
        }
        val imported = PsiTreeUtil.getParentOfType(leaf, ES6ImportSpecifier::class.java, false)
            ?: PsiTreeUtil.getParentOfType(leaf, ES6ImportedBinding::class.java, false)
        if (imported != null) {
            val name = (imported as? ES6ImportSpecifier)?.let { it.alias?.name ?: it.referenceName } ?: (imported as? JSNamedElement)?.name
            return name?.let { Selection(it, false, unavailable = "imported: add it from its own file") }
        }
        val enclosing = PsiTreeUtil.getParentOfType(leaf, JSNamedElement::class.java, false)
        val declaration = enclosing?.takeIf {
            (it as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.containsOffset(offset) == true
        }
        val reference = PsiTreeUtil.getParentOfType(leaf, JSReferenceExpression::class.java, false)
        val named = declaration ?: (reference?.reference?.resolve() as? JSNamedElement) ?: return null
        if (named.containingFile != file) {
            val name = named.name ?: return null
            if (reference?.textRange?.containsOffset(offset) != true && declaration == null) return null
            return Selection(name, false, unavailable = "from ${named.containingFile.name}: add it there")
        }
        val identifier = (named as? PsiNameIdentifierOwner)?.nameIdentifier ?: return null
        if (declaration == null && reference?.textRange?.containsOffset(offset) != true) return null
        val name = named.name ?: return null
        val directExportName = when (named) {
            is JSFunction -> if (named.isExportedWithDefault) "default" else if (named.isExported) name else null
            is JSVariable -> if (named.isExportedWithDefault) "default" else if (named.isExported) name else null
            is JSClass -> if (named.isExportedWithDefault) "default" else if (named.isExported) name else null
            else -> null
        }
        var parent = named.parent
        var nested = false
        while (parent != null && parent !is PsiFile) {
            if (parent is JSNamedElement) nested = true
            parent = parent.parent
        }
        val listed = file.children.filterIsInstance<ES6ExportDeclaration>()
            .flatMap { it.exportSpecifiers.asIterable() }
            .firstOrNull { it.reference?.resolve() == named }
        val exportName = listed?.alias?.name ?: listed?.referenceName
        val selectedExport = exportName ?: directExportName?.takeUnless { nested }
        val unavailable = when {
            named is TypeScriptInterface || named is TypeScriptTypeAlias -> "a type has no value"
            file.name.endsWith(".d.ts") || isAmbient(named) -> "a declaration only, without a value"
            named is ES6ImportedBinding || named is ES6ImportSpecifier || named is ES6ImportSpecifierAlias -> "imported: add it from its own file"
            selectedExport == null && nested -> "not a top-level name"
            // TypeScript compiled to CommonJS shares only module.exports with the console.
            selectedExport == null && file.virtualFile?.extension.equals("cts", ignoreCase = true) -> "not exported from a .cts file"
            else -> null
        }
        return Selection(name, selectedExport != null, selectedExport?.takeIf { it != name }, !nested, unavailable)
    }

    private fun isAmbient(element: PsiElement): Boolean = generateSequence(element) { it.parent }.takeWhile { it !is PsiFile }
        .any { PsiTreeUtil.getChildOfType(it, JSAttributeList::class.java)?.node?.findChildByType(JSTokenTypes.DECLARE_KEYWORD) != null }
}

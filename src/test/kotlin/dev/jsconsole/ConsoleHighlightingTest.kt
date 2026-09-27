package dev.jsconsole

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.UIUtil
import dev.jsconsole.ui.ConsoleInputHighlighting
import dev.jsconsole.ui.ConsoleSyntaxPrinter
import com.intellij.execution.ui.ConsoleViewContentType

class ConsoleHighlightingTest : BasePlatformTestCase() {
    fun testChainedMethodColors() {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("js") as LanguageFileType
        val input = LanguageTextField(fileType.language, project, "", false)
        input.setDisposedWith(testRootDisposable)
        input.getEditor(true)
        ConsoleInputHighlighting(project, input.document, testRootDisposable)
        val documents = PsiDocumentManager.getInstance(project)
        val psi = documents.getPsiFile(input.document)!!
        myFixture.configureFromExistingVirtualFile(psi.virtualFile)
        val segments = listOf("new Date().toISOString()", ".split(/\\D/)", ".filter(Boolean)", ".reduce((a, b) => a + b)")
        val methods = listOf("toISOString", "split", "filter", "reduce")
        fun assertMethodColors(expected: List<String>) {
            documents.commitAllDocuments()
            UIUtil.dispatchAllInvocationEvents()
            val highlights = myFixture.doHighlighting()
            for (method in expected) {
                val start = input.text.indexOf(method)
                assertTrue("Missing method color for $method in ${input.text}", highlights.any {
                    it.startOffset == start && it.endOffset == start + method.length &&
                        it.forcedTextAttributesKey?.externalName == "JS.INSTANCE_MEMBER_FUNCTION"
                })
            }
        }
        for ((index, segment) in segments.withIndex()) {
            // Append in place, as typing does: replacing the entire document masks stale ranges.
            WriteCommandAction.runWriteCommandAction(project) {
                input.document.insertString(input.document.textLength, segment)
            }
            assertMethodColors(methods.take(index + 1))
        }
        val complete = input.text
        WriteCommandAction.runWriteCommandAction(project) {
            input.document.deleteString(input.text.indexOf(".reduce"), input.document.textLength)
        }
        assertMethodColors(methods.take(3))
        input.text = ""
        input.text = complete // Execution followed by history recall.
        assertMethodColors(methods)
        val printer = ConsoleSyntaxPrinter(project, fileType)
        val styles = printer.capture(input.getEditor(true)!!)
        input.text = "" // Captured styles must survive execution clearing the field.
        val chunks = mutableListOf<Pair<String, ConsoleViewContentType>>()
        printer.print(complete, styles) { text, type -> chunks.add(text to type) }
        assertEquals(complete, chunks.joinToString("") { it.first })
        for (method in methods) {
            assertEquals("JS.INSTANCE_MEMBER_FUNCTION", chunks.single { it.first == method }.second.attributesKey?.externalName)
        }
        assertEquals("JS.GLOBAL_FUNCTION", chunks.single { it.first == "Boolean" }.second.attributesKey?.externalName)
        chunks.clear()
        printer.print("['2026', 42, true]") { text, type -> chunks.add(text to type) }
        assertTrue(chunks.single { it.first == "'2026'" }.second != ConsoleViewContentType.NORMAL_OUTPUT)
    }
}

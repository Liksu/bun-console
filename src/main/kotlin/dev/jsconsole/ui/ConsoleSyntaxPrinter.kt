package dev.jsconsole.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx

/** Lexical coloring also works for incomplete commands and unresolved runtime bindings. */
class ConsoleSyntaxPrinter(project: Project, fileType: FileType) {
    private val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null)
    private val types = mutableMapOf<TextAttributesKey, ConsoleViewContentType>()

    fun capture(editor: EditorEx): List<ConsoleStyle> =
        (editor.filteredDocumentMarkupModel.allHighlighters.asSequence() + editor.markupModel.allHighlighters.asSequence())
            .filter { it.isValid && it.startOffset < it.endOffset }
            .sortedBy { it.layer }
            .mapNotNull { range ->
                range.textAttributesKey?.takeIf { it.externalName.startsWith("JS.") }?.let {
                    ConsoleStyle(range.startOffset, range.endOffset, it)
                }
            }.toList()

    fun print(source: String, styles: List<ConsoleStyle> = emptyList(), append: (String, ConsoleViewContentType) -> Unit) {
        val highlighter = highlighter ?: run {
            append(source, ConsoleViewContentType.NORMAL_OUTPUT)
            return
        }
        val lexer = highlighter.highlightingLexer
        lexer.start(source)
        while (lexer.tokenType != null) {
            val key = styles.lastOrNull { it.start <= lexer.tokenStart && it.end >= lexer.tokenEnd }?.key
                ?: highlighter.getTokenHighlights(lexer.tokenType!!).lastOrNull()
            val type = if (key == null) ConsoleViewContentType.NORMAL_OUTPUT else
                types.getOrPut(key) { ConsoleViewContentType("JS Console ${key.externalName}", key) }
            append(source.substring(lexer.tokenStart, lexer.tokenEnd), type)
            lexer.advance()
        }
    }
}

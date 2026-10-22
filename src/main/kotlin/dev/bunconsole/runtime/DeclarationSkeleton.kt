package dev.bunconsole.runtime

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportCall
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSDestructuringElement
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSPrefixExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSSpreadExpression
import com.intellij.lang.javascript.psi.JSStaticBlock
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.TypeScriptSatisfiesExpression
import com.intellij.lang.javascript.psi.ecma6.ES6Decorator
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.ecma6.JSTypeDeclaration
import com.intellij.lang.javascript.psi.ecma6.TypeScriptAsExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptCastExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecma6.TypeScriptImportStatement
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.ecma6.TypeScriptNotNullExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeAlias
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeArgumentList
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * The declarations of a file, without running it: what Run Selection uses for a file that has
 * not run yet. A purely syntactic rule decides what stays. Functions and classes stay; a variable
 * keeps its initializer only when that is built from literals, functions, earlier such names and
 * built-ins that run no code of the project (`new Map()`, `Math.max(…)`), never `db()` or `fetch()`.
 * Everything else (statements, other initializers) becomes spaces, so lines and columns stay.
 *
 * [names] are the names whose values the skeleton knows; the console asks to run the file for others.
 */
object DeclarationSkeleton {
    data class Skeleton(val source: String, val names: List<String>)

    /** Built-in values a safe initializer may read. */
    private val GLOBALS = setOf(
        "undefined", "NaN", "Infinity", "Math", "JSON", "Object", "Array", "Number", "String", "Boolean",
        "Symbol", "BigInt", "Date", "RegExp", "Map", "Set", "WeakMap", "WeakSet", "Promise", "Intl", "URL",
        "URLSearchParams", "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray",
        "Int16Array", "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
        "BigInt64Array", "BigUint64Array", "TextEncoder", "TextDecoder", "Error", "TypeError", "RangeError",
        "SyntaxError", "ReferenceError", "URIError", "EvalError", "AggregateError",
        "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURIComponent", "decodeURIComponent",
        "encodeURI", "decodeURI", "structuredClone",
    )

    /** Built-ins that may be constructed: they keep the values they are given and call nothing of the project. */
    private val CONSTRUCTORS = setOf(
        "Map", "Set", "WeakMap", "WeakSet", "Date", "RegExp", "Array", "Object", "URL", "URLSearchParams",
        "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array",
        "Int32Array", "Uint32Array", "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array",
        "TextEncoder", "TextDecoder", "Error", "TypeError", "RangeError", "SyntaxError", "ReferenceError",
        "URIError", "EvalError", "AggregateError", "Intl.NumberFormat", "Intl.DateTimeFormat", "Intl.Collator",
        "Intl.PluralRules", "Intl.RelativeTimeFormat", "Intl.ListFormat",
    )

    /** Built-in functions that may be called (Math.* in full). */
    private val CALLS = setOf(
        "JSON.parse", "JSON.stringify", "Object.freeze", "Object.seal", "Object.keys", "Object.values",
        "Object.entries", "Object.fromEntries", "Object.assign", "Object.create", "Object.is",
        "Array.from", "Array.of", "Array.isArray", "Number", "Number.parseInt", "Number.parseFloat",
        "Number.isInteger", "Number.isFinite", "Number.isNaN", "Number.isSafeInteger", "String",
        "String.fromCharCode", "String.fromCodePoint", "Boolean", "Symbol", "Symbol.for", "BigInt",
        "Date", "Date.now", "Date.parse", "Date.UTC", "RegExp", "Promise.resolve", "Promise.reject",
        "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURIComponent", "decodeURIComponent",
        "encodeURI", "decodeURI", "structuredClone",
    )

    /** Methods that may be called on a value the skeleton already knows (a string, an array). */
    private val METHODS = setOf(
        "at", "charAt", "charCodeAt", "codePointAt", "concat", "endsWith", "includes", "indexOf", "lastIndexOf",
        "join", "keys", "values", "entries", "padEnd", "padStart", "repeat", "replace", "replaceAll", "slice",
        "split", "startsWith", "substring", "toLowerCase", "toUpperCase", "trim", "trimStart", "trimEnd",
        "toFixed", "toPrecision", "toString", "flat", "toSorted", "toReversed", "normalize", "get", "has",
    )

    fun build(file: PsiFile): Skeleton {
        val text = file.text.toCharArray()
        // `export default function f() {}` is a declaration of f, like `export function f() {}`.
        val exportedDefault = mutableMapOf<PsiElement, PsiElement>()
        val elements = file.children.filterNot { it is PsiWhiteSpace || it is PsiComment }.map { element ->
            val declaration = if (element !is ES6ExportDefaultAssignment) null
                else (PsiTreeUtil.getChildOfType(element, JSFunction::class.java) ?: PsiTreeUtil.getChildOfType(element, JSClass::class.java))
                    ?.takeIf { (it as PsiNamedElement).name != null }
            declaration?.also { exportedDefault[it] = element } ?: element
        }
        val declared = mutableSetOf<String>()    // shadow the built-ins
        val functions = mutableSetOf<String>()   // hoisted, but never passed to a built-in that could call them
        val classes = mutableSetOf<String>()
        for (element in elements) {
            when (element) {
                is TypeScriptInterface, is TypeScriptTypeAlias -> {}
                is TypeScriptEnum -> element.name?.let { declared += it }
                is JSFunction -> element.name?.let { declared += it; functions += it }
                is JSClass -> element.name?.let { declared += it; classes += it }
                is JSVarStatement -> element.variables.mapNotNullTo(declared) { it.name }
                is ES6ImportDeclaration -> imports(element).forEach { declared += it }
                is TypeScriptImportStatement -> (element as? PsiNamedElement)?.name?.let { declared += it }
            }
        }
        val known = mutableSetOf<String>()       // safe to read when an initializer runs
        known += functions
        elements.filterIsInstance<ES6ImportDeclaration>().forEach { known += imports(it) }
        elements.filterIsInstance<TypeScriptImportStatement>().forEach { statement -> (statement as? PsiNamedElement)?.name?.let { known += it } }
        val values = linkedSetOf<String>()       // the names the skeleton exports
        val names = linkedSetOf<String>()        // …of which these have their file's initial values
        names += functions
        values += functions
        val check = Check(known, declared, functions, classes)

        for (element in elements) {
            exportedDefault[element]?.let { unexport(text, it) }
            if (isAmbient(element)) { blank(text, element); continue }
            when (element) {
                is ES6ImportDeclaration, is TypeScriptImportStatement, is TypeScriptInterface, is TypeScriptTypeAlias -> {}
                is JSFunction -> if (element.name == null) blank(text, element) else unexport(text, element)
                is TypeScriptEnum -> {
                    val name = element.name ?: run { blank(text, element); null } ?: continue
                    values += name
                    // Members may refer to earlier members of the same enum.
                    val members = element.fields.mapNotNull { it.name }
                    if (Check((known + members).toMutableSet(), emptySet(), emptySet(), emptySet()).childrenSafe(element)) {
                        unexport(text, element); known += name; names += name
                    } else replace(text, element, "let $name;")
                }
                is JSClass -> {
                    val name = element.name
                    if (name == null) { blank(text, element); continue }
                    values += name
                    if (check.classSafe(element)) { unexport(text, element); known += name; names += name }
                    else replace(text, element, "let $name;")
                }
                is JSVarStatement -> variables(text, element, check, values, names)
                else -> blank(text, element) // statements, `export { … }`, `export default …`, namespaces
            }
        }
        val source = String(text) + if (values.isEmpty()) "" else "\n;export { ${values.joinToString()} };\n"
        return Skeleton(source, names.toList())
    }

    private fun variables(text: CharArray, statement: JSVarStatement, check: Check, values: MutableSet<String>, names: MutableSet<String>) {
        val variables = statement.variables.filter { it.name != null }
        if (variables.isEmpty()) { blank(text, statement); return }
        // A destructuring pattern needs its initializer: it stays whole, or goes whole.
        if (PsiTreeUtil.getChildOfType(statement, JSDestructuringElement::class.java) != null || variables.any { it.parent != statement }) {
            if (check.childrenSafe(statement)) {
                unexport(text, statement)
                variables.forEach { values += it.name!!; names += it.name!!; check.known += it.name!! }
            } else blank(text, statement)
            return
        }
        unexport(text, statement)
        var needsLet = false
        for (variable in variables) {
            val name = variable.name!!
            values += name
            val initializer = variable.initializer
            if (initializer == null || check.safe(initializer)) {
                names += name
                check.known += name
            } else {
                // Declared without a value; the console asks to run the file to read it.
                val equals = generateSequence(variable.firstChild) { it.nextSibling }.firstOrNull { it.text == "=" }
                val start = equals?.textRange?.startOffset ?: initializer.textRange.startOffset
                blank(text, start, initializer.textRange.endOffset)
                needsLet = true
            }
        }
        val const = statement.node.findChildByType(JSTokenTypes.CONST_KEYWORD)
        if (needsLet && const != null) "let  ".forEachIndexed { index, char -> text[const.startOffset + index] = char }
    }

    private fun imports(declaration: ES6ImportDeclaration): List<String> =
        declaration.importedBindings.mapNotNull { it.name } + declaration.importSpecifiers.mapNotNull { it.alias?.name ?: it.referenceName }

    private fun isAmbient(element: PsiElement): Boolean =
        PsiTreeUtil.getChildOfType(element, JSAttributeList::class.java)?.node?.findChildByType(JSTokenTypes.DECLARE_KEYWORD) != null ||
            element.node.findChildByType(JSTokenTypes.DECLARE_KEYWORD) != null

    /** `export` and `default` go: the skeleton lists its exports itself, once. */
    private fun unexport(text: CharArray, element: PsiElement) {
        for (list in PsiTreeUtil.getChildrenOfTypeAsList(element, JSAttributeList::class.java) + listOf(element)) {
            var child = list.firstChild
            while (child != null) {
                val type = child.node.elementType
                if (type == JSTokenTypes.EXPORT_KEYWORD || type == JSTokenTypes.DEFAULT_KEYWORD) blank(text, child)
                child = child.nextSibling
            }
        }
    }

    private fun blank(text: CharArray, element: PsiElement) = blank(text, element.textRange.startOffset, element.textRange.endOffset)

    private fun blank(text: CharArray, start: Int, end: Int) {
        for (index in start until end) if (text[index] != '\n' && text[index] != '\r') text[index] = ' '
    }

    /** A declaration the skeleton cannot run keeps its name, without a value. */
    private fun replace(text: CharArray, element: PsiElement, declaration: String) {
        blank(text, element)
        val start = element.textRange.startOffset
        if (element.textLength >= declaration.length) declaration.forEachIndexed { index, char -> text[start + index] = char }
    }

    /**
     * The syntactic rule. [known] names have safe values; [shadowed] names are the file's own
     * (not built-ins); [functions] and [classes] must not reach code that could call them.
     */
    private class Check(val known: MutableSet<String>, val shadowed: Set<String>, val functions: Set<String>, val classes: Set<String>) {

        fun childrenSafe(element: PsiElement): Boolean =
            generateSequence(element.firstChild) { it.nextSibling }.all { safe(it) }

        fun safe(element: PsiElement): Boolean = when (element) {
            is JSTypeDeclaration, is TypeScriptTypeArgumentList, is PsiComment, is PsiWhiteSpace -> true
            // A function is a value; its body runs only when called. Object literal accessors run on reading.
            is JSFunction -> !element.isGetProperty && !element.isSetProperty
            is JSClass -> classSafe(element)
            is JSAssignmentExpression -> false
            is JSCallExpression -> callSafe(element)
            is JSReferenceExpression -> referenceSafe(element)
            is JSPrefixExpression -> element.node.findChildByType(UNSAFE_PREFIXES) == null && childrenSafe(element)
            is JSStringTemplateExpression -> element.text.startsWith("`") && childrenSafe(element) // a tag would be a call
            is JSLiteralExpression, is JSArrayLiteralExpression, is JSObjectLiteralExpression, is JSParenthesizedExpression,
            is JSConditionalExpression, is JSSpreadExpression, is JSIndexedPropertyAccessExpression, is TypeScriptAsExpression,
            is TypeScriptSatisfiesExpression, is TypeScriptNotNullExpression, is TypeScriptCastExpression -> childrenSafe(element)
            is JSBinaryExpression -> childrenSafe(element)
            is ES6ImportCall -> false
            is JSExpression -> false // anything else: `this`, `await`, `yield`, `x++`, comma, …
            else -> element.node.elementType !in UNSAFE_TOKENS && childrenSafe(element)
        }

        private fun referenceSafe(reference: JSReferenceExpression): Boolean {
            val qualifier = reference.qualifier ?: return reference.referenceName.let { name ->
                name != null && (name in known || (name in GLOBALS && name !in shadowed))
            }
            // A class may have static getters: reading its properties could run its code.
            if (qualifier is JSReferenceExpression && qualifier.qualifier == null && qualifier.referenceName in classes) return false
            return safe(qualifier)
        }

        private fun callSafe(call: JSCallExpression): Boolean {
            val arguments = call.argumentList?.arguments.orEmpty()
            // A built-in could call a function it is given (Array.from(xs, f)).
            if (arguments.any { argument -> isFunction(argument) || !safe(argument) }) return false
            val callee = call.methodExpression as? JSReferenceExpression ?: return false
            val path = builtinPath(callee)
            if (call is JSNewExpression) return path != null && path in CONSTRUCTORS
            if (path != null) return path in CALLS || (path.startsWith("Math.") && path.count { it == '.' } == 1)
            val qualifier = callee.qualifier ?: return false
            return callee.referenceName in METHODS && safe(qualifier)
        }

        private fun isFunction(argument: JSExpression): Boolean {
            val value = (argument as? JSSpreadExpression)?.expression ?: argument
            return value is JSFunction || value is JSClass ||
                (value is JSReferenceExpression && value.qualifier == null && (value.referenceName in functions || value.referenceName in classes))
        }

        /** "Math.max" for a reference to a built-in that the file does not declare itself. */
        private fun builtinPath(reference: JSReferenceExpression): String? {
            val parts = mutableListOf<String>()
            var current: JSExpression? = reference
            while (current is JSReferenceExpression) {
                parts.add(0, current.referenceName ?: return null)
                current = current.qualifier
            }
            if (current != null || parts.first() !in GLOBALS || parts.first() in shadowed) return null
            return parts.joinToString(".")
        }

        /** Methods, getters and instance fields run later; static parts, decorators and `extends` run with the class. */
        fun classSafe(element: JSClass): Boolean {
            if (PsiTreeUtil.findChildrenOfType(element, ES6Decorator::class.java)
                    .any { PsiTreeUtil.getParentOfType(it, JSClass::class.java) == element }) return false
            if (element.extendsList?.expressions?.all { safe(it) } == false) return false
            for (member in generateSequence(element.firstChild) { it.nextSibling }) {
                when (member) {
                    is JSStaticBlock -> return false
                    is JSFunction -> {}
                    is JSVarStatement, is JSVariable -> if (isStatic(member)) {
                        val initializers = if (member is JSVarStatement) member.variables.mapNotNull { it.initializer }
                            else listOfNotNull((member as JSVariable).initializer)
                        if (!initializers.all { safe(it) }) return false
                    }
                }
            }
            return true
        }

        private fun isStatic(member: PsiElement): Boolean =
            PsiTreeUtil.findChildrenOfType(member, JSAttributeList::class.java).any { it.node.findChildByType(JSTokenTypes.STATIC_KEYWORD) != null }
    }

    private val UNSAFE_PREFIXES = com.intellij.psi.tree.TokenSet.create(
        JSTokenTypes.AWAIT_KEYWORD, JSTokenTypes.DELETE_KEYWORD, JSTokenTypes.PLUSPLUS, JSTokenTypes.MINUSMINUS)
    private val UNSAFE_TOKENS = com.intellij.psi.tree.TokenSet.create(JSTokenTypes.AWAIT_KEYWORD)
}

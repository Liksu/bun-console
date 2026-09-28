package dev.jsconsole.debug

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

object PausedFrameEvaluator {
    /** Evaluate in the selected frame of any suspended session; fails if it resumes, steps or stops first. */
    fun evaluateIn(session: XDebugSession, source: String): CompletableFuture<String> {
        val frame = session.currentStackFrame
        val evaluator = frame?.evaluator
            ?: return CompletableFuture.failedFuture(IllegalStateException("Select a stack frame with JavaScript evaluation support"))
        val result = evaluate(evaluator, source, session.currentPosition) {
            !session.isSuspended || session.currentStackFrame !== frame
        }
        val listening = Disposer.newDisposable("JS Console paused evaluation")
        val obsolete = { result.completeExceptionally(IllegalStateException("Debugger frame changed or resumed")); Unit }
        session.addSessionListener(object : XDebugSessionListener {
            override fun beforeSessionResume() = obsolete()
            override fun sessionResumed() = obsolete()
            override fun sessionStopped() = obsolete()
            override fun stackFrameChanged() = obsolete()
        }, listening)
        result.whenComplete { _, _ -> Disposer.dispose(listening) }
        return result
    }

    fun evaluate(evaluator: XDebuggerEvaluator, source: String, position: XSourcePosition?, obsolete: () -> Boolean): CompletableFuture<String> {
        val result = CompletableFuture<String>().orTimeout(15, TimeUnit.SECONDS)
        evaluator.evaluate(source, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun errorOccurred(errorMessage: String) { result.completeExceptionally(IllegalStateException(errorMessage)) }
            override fun evaluated(value: XValue) {
                if (obsolete()) { errorOccurred("Debugger frame changed or resumed"); return }
                value.computePresentation(object : XValueNode {
                    override fun isObsolete() = result.isDone || obsolete()
                    override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) = Unit
                    override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                        if (!isObsolete) result.complete(value.ifEmpty { type ?: "undefined" })
                    }
                    override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                        val text = StringBuilder()
                        presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                            override fun renderValue(value: String) { text.append(value) }
                            override fun renderStringValue(value: String) { text.append('"').append(value).append('"') }
                            override fun renderNumericValue(value: String) = renderValue(value)
                            override fun renderKeywordValue(value: String) = renderValue(value)
                            override fun renderValue(value: String, key: TextAttributesKey) = renderValue(value)
                            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) = renderStringValue(value)
                            override fun renderComment(comment: String) = renderValue(comment)
                            override fun renderSpecialSymbol(symbol: String) = renderValue(symbol)
                            override fun renderError(error: String) = renderValue(error)
                        })
                        if (!isObsolete) result.complete(text.toString().ifEmpty { presentation.type ?: "undefined" })
                    }
                }, XValuePlace.TOOLTIP)
            }
        }, position)
        return result
    }
}

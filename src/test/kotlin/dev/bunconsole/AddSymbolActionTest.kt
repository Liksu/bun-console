package dev.bunconsole

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.bunconsole.ui.AddSymbolAction

class AddSymbolActionTest : ConsoleTestCase() {
    fun testExportedAndModuleLocalDeclarations() {
        val action = AddSymbolAction()
        val file = myFixture.configureByText("a.ts", """
            export function twice(value: number) { return value * 2 }
            export const lexical = "visible";
            const testHighlight = new Date().toISOString();
            export const object = { hiddenMethod() { return 1 } };
            const shared = 5; export { shared as publicName };
            export class Box {}
            export default function privateDefault() { return 3 }
            const used = lexical + 1;
        """.trimIndent())
        fun select(name: String): AddSymbolAction.Selection? = action.symbolAt(file, file.text.indexOf(name))
        assertEquals(AddSymbolAction.Selection("twice", true), select("twice"))
        assertEquals(AddSymbolAction.Selection("lexical", true), select("lexical"))
        assertEquals(AddSymbolAction.Selection("testHighlight", false), select("testHighlight"))
        assertEquals(AddSymbolAction.Selection("hiddenMethod", false, topLevel = false, unavailable = "not a top-level name"), select("hiddenMethod"))
        assertEquals(AddSymbolAction.Selection("shared", true, "publicName"), select("shared"))
        assertEquals(AddSymbolAction.Selection("Box", true), select("Box"))
        assertEquals(AddSymbolAction.Selection("privateDefault", true, "default"), select("privateDefault"))
        assertEquals(AddSymbolAction.Selection("lexical", true), action.symbolAt(file, file.text.lastIndexOf("lexical")))
        assertEquals(AddSymbolAction.Selection("shared", true, "publicName"), action.symbolAt(file, file.text.indexOf("publicName")))
    }

    /** Add Symbol is disabled where the console could not provide the name, and says why. */
    fun testDisabledWhereTheConsoleHasNoValue() {
        val action = AddSymbolAction()
        myFixture.addFileToProject("dep.ts", "export const dep = 1;")
        val file = myFixture.configureByText("shapes.ts", """
            import { dep } from './dep';
            export interface Point { x: number }
            type Id = string;
            declare const ambient: number;
            function outer() { const inner = 1; return inner; }
            const plain = dep + 1;
        """.trimIndent())
        fun reason(name: String, occurrence: Int = 0): String? {
            var offset = -1
            repeat(occurrence + 1) { offset = file.text.indexOf(name, offset + 1) }
            return action.symbolAt(file, offset)?.unavailable
        }
        assertEquals("imported: add it from its own file", reason("dep"))
        assertEquals("from dep.ts: add it there", reason("dep", 2))
        assertEquals("a type has no value", reason("Point"))
        assertEquals("a type has no value", reason("Id"))
        assertEquals("a declaration only, without a value", reason("ambient"))
        assertEquals("not a top-level name", reason("inner"))
        assertNull(reason("outer"))
        assertNull(reason("plain"))

        fun presentation(offset: Int) = run {
            myFixture.editor.caretModel.moveToOffset(offset)
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, myFixture.editor)
                .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile).build()
            val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.POPUP, null)
            event.presentation.text = "Add Symbol to Bun Console"
            action.update(event)
            event.presentation
        }
        val type = presentation(file.text.indexOf("Point"))
        assertTrue(type.isVisible)
        assertFalse(type.isEnabled)
        assertEquals("Add Symbol to Bun Console (a type has no value)", type.text)
        assertTrue(presentation(file.text.indexOf("plain")).isEnabled)

        val compiled = myFixture.configureByText("legacy.cts", "const local = 1;\nexport const shared = local;")
        assertEquals("not exported from a .cts file", action.symbolAt(compiled, compiled.text.indexOf("local"))?.unavailable)
        assertNull(action.symbolAt(compiled, compiled.text.indexOf("shared"))?.unavailable)
    }
}

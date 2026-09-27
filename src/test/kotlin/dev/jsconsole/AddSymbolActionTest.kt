package dev.jsconsole

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.jsconsole.ui.AddSymbolAction

class AddSymbolActionTest : BasePlatformTestCase() {
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
        assertEquals(AddSymbolAction.Selection("hiddenMethod", false), select("hiddenMethod"))
        assertEquals(AddSymbolAction.Selection("shared", true, "publicName"), select("shared"))
        assertEquals(AddSymbolAction.Selection("Box", true), select("Box"))
        assertEquals(AddSymbolAction.Selection("privateDefault", true, "default"), select("privateDefault"))
        assertEquals(AddSymbolAction.Selection("lexical", true), action.symbolAt(file, file.text.lastIndexOf("lexical")))
        assertEquals(AddSymbolAction.Selection("shared", true, "publicName"), action.symbolAt(file, file.text.indexOf("publicName")))
    }
}

# Console acceptance checks

Target: WebStorm 2026.2.3, Bun 1.4+. Always-on debugging is blocked as documented
in [debugger-blocker.md](debugger-blocker.md).

## Automated runtime checks

```powershell
bun test .\tests\runtime\bootstrap.test.mjs
```

Checks persistent variables, TypeScript imports, bare export calls, global and
lexical collisions (including an `undefined` binding), preserving overwritten
bindings, switching modules, top-level await, sync/async/syntax errors, a failed
import retaining the previous context, output/control separation, and fresh state
after restart. All fixtures are inside this development project.

## Platform and JVM checks

```powershell
.\gradlew.bat -PwebstormPath="C:\path\to\WebStorm" test buildPlugin verifyPlugin
```

JVM tests cover the actual socket/process bridge, startup failure, stopping an
infinite expression, and an IDE project fixture with file follow, pin, first-command
queueing, restart, and retained history. Consult generated reports for actual
results; merely having the test source is not a pass.

The panel regression test also creates its real input editor and invokes the
registered `EditorUp`/`EditorDown` handlers. It covers recalled multiline commands,
draft restoration, history boundaries, ordinary caret movement, completion-popup
priority, and unaffected file editors. It checks the configured editor font,
input lexer coloring after text replacement, and colored command transcript replay.
This is a headless platform test, not a physical-keyboard/focus acceptance test.

History regression coverage includes editing multiple recalled commands and
revisiting them in both directions without losing changes. The highlighting test
appends the user's Date/toISOString/split/filter/reduce chain incrementally,
checks semantic method colors across the entire chain at each stage, then checks
deletion and clearing/recall. Interactive confirmation remains necessary because
the fixture drives analysis explicitly rather than through a focused window.

The output-color test snapshots actual semantic markup from the input, clears the
input, and verifies that every method plus the global `Boolean` keeps its color in
the printed command. Result strings receive lexical colors. The completion test
accepts `lexical` and an export from an added file with Tab, evaluating both bare
identifiers in Bun, while checking that ordinary files receive no console-specific
suggestions. A separate test edits a loaded TS file and confirms that a project
export absent from the running context cannot insert an auto-import. It then
reimports the file without resetting console variables, checks that Tab inserts
the new export as a bare identifier and evaluates it, and repeats the refresh
for an added file. An edited imported dependency requests a full Restart.
Service tests queue opening and Restart from `ModalityState.any()` with modified
physical documents, then verify evaluation uses the saved contents. Clear is
tested through its menu action, including preserved draft/history/runtime and empty
transcript on reattachment.

Import tests parse raw default/named/aliased/namespace/side-effect declarations,
including semicolon-free input and escaped module strings. Strings, comments and
dynamic imports stay unchanged. Service tests execute the user's `import path`
example, reuse `path` in another command and import aliases from a sibling TS file.
Runtime tests cover repeat imports, live exports after switching context,
collisions, missing modules/exports, and recovery after errors.
Additional-file tests import two TS modules beside a following main context,
check automatic collision/default aliases, live bindings, and removal. The IDE
fixture checks added-file restoration after Restart and that removing the file
does not remove an independently added symbol.

Plugin Verifier is configured to fail on all reported categories, including
internal, experimental, deprecated, and override-only API usage.

## Source inspections still pending

The separate IntelliJ source-inspection gate has NOT run. The only installed IDE
found is WebStorm; the JVM inspections needed for the Kotlin plugin are supplied
by IntelliJ IDEA / Qodana for JVM. A profile with verified inspection IDs is
prepared at `config/inspections/api-usage.xml`. It needs a JVM-capable inspection
environment and an imported project with the correct SDK/dependencies. Plugin
Verifier passing is not recorded as a substitute for this source inspection gate.

Reference inspection IDs:
[UnstableApiUsage](https://www.jetbrains.com/help/inspectopedia/UnstableApiUsage.html),
[UnstableTypeUsedInSignature](https://www.jetbrains.com/help/inspectopedia/UnstableTypeUsedInSignature.html),
[NonExtendableApiUsage](https://www.jetbrains.com/help/inspectopedia/NonExtendableApiUsage.html),
[OverrideOnly](https://www.jetbrains.com/help/inspectopedia/OverrideOnly.html).

## Remaining interactive checks in an isolated IDE

Launch `runIde` only with separate config/system/plugin directories (the Gradle
configuration uses `build/isolated-ide`). Open a disposable JS/TS project.

1. Before opening JS Console, no Bun backend should exist.
2. Click its stripe button: UI appears immediately, and typing during startup works.
3. Check highlighting/completion, multiline Enter, Ctrl+Enter, Up/Down history.
   Fill the input with more lines than it can show and verify that
   the mouse wheel scrolls the input while the pointer is over it. Drag its
   upper divider to change the input height.
   In Settings → Tools → JS Console, switch to Enter-to-run. Verify Enter runs,
   Shift+Enter inserts an indented newline, Ctrl+Enter no longer runs, and Enter
   still accepts an open completion suggestion. Switch back and verify the
   default keys return without reopening the console.
   Edit a recalled command, visit another entry with Up/Down, then return: the
   edited value must remain. Gradually extend `new Date().toISOString()` with
   `.split(/\D/).filter(Boolean).reduce((a, b) => a + b)` and verify that earlier
   method names keep their semantic colors after each edit settles.
   Run `const value = 1; value`, recall it, change it to `const value = 2; value`,
   and run again: the second result and a subsequent `value` query should both
   be `2`. Two `const value` declarations in one command must still fail.
4. Open a TS file exporting `twice(x: number)`, then call `twice(21)` -> `42`.
5. Pin file `a.ts`, switch to `b.ts`, and verify both are available while the
   active tab says `b.ts · follows editor`. Return to `a.ts`, unpin it, and
   verify it disappears from the pinned context after switching again.
6. Restart: prior text stays, variables disappear, current/pinned exports return.
7. Run `while (true) {}` and use Restart; the IDE must remain responsive.
8. Close the project; its own Bun process and socket should be released.
9. Complete `lex` with Tab in the context of the fixture's `a.ts`: input must be
   `lexical`, without an inserted import; execute it successfully.
   Then add `export const initialText = 'new'` to the loaded file. Completion
   must not insert `import` while the runtime still has the old module. Focus
   the console input: it should reimport the file without losing console
   variables. Complete `ini` with Tab and evaluate `initialText`.
10. Clear: output disappears, the input/history/variables remain; new output works.
11. Verify the stripe icon is white on the selected blue background in dark/light
    themes, and commands in the output retain the semantic colors of the input.
12. Run `import path from 'path'` followed by
    `path.join(process.cwd(), 'texts')` in the same command, then reuse `path` in a
    later command. Repeat the import; it should succeed. Import a sibling file's
    export with an alias, switch context files, and confirm the alias still works.
13. Open Settings → Tools → JS Console. Switch between automatic detection and
    an explicit Bun path; invalid paths must not be saved. Apply while a console
    variable exists: it must remain available until Restart. Reopen settings and
    restart the test IDE to confirm the choice is retained. The toolbar must have
    no Bun picker button.
14. Verify the tool window's native **⋮** menu contains the console commands and
    its standard IDE window-management options. Pin File Context must show a
    check mark matching the active state. Run Input is disabled for blank input;
    Ctrl+Enter executes from the input, including with completion open, and does
    not replace shortcuts in ordinary editors. Confirm the selected file/runtime
    status is visible in the header and that no full-size command buttons remain.
15. From a second TS file's editor or Project-tree menu choose **Add File to JS
    Console**. Its exports should work alongside the current file, and the header
    should show one native tab per context file (`[b.ts · follows editor] [a.ts · pinned]`), without
    a combined `+ 1 file` label. Selecting either tab must preserve the same
    console input, transcript, and runtime. Switch editor tabs and Restart: those exports and
    completion suggestions should remain. Then choose **Remove File from JS
    Console**; its added names should disappear while the current file and other
    added files remain. A non-exported top-level variable must stay inaccessible.
    Give both files an export called `shared`: `shared` must refer to the file
    following the editor, and each value must be reachable via
    `globalThis['a.ts'].shared` or `globalThis['b.ts'].shared`. Repeat with two
    `a.ts` files in different folders and verify both pills and namespace keys
    expand to unique `folder/a.ts` paths.
    Run `const shared = 42` twice with different values: the short name must
    show the latest console value, while `globalThis['a.ts'].shared` still shows
    the file export.

Do not install into, attach to, restart, or change settings of the user's working
WebStorm without explicit approval.

# JS Console for WebStorm

A WebStorm plugin project intended to provide a persistent Bun console with
direct access to the current file's exports and integrated debugging.

Requirements and acceptance criteria: [handout](CODEX_HANDOUT_JS_CONSOLE.md).

Current stage: **console with additional file contexts built; automated checks pass**.
Interactive acceptance and separate JVM source inspections are still pending.
See [findings](docs/phase-0-findings.md), [acceptance checks](docs/acceptance.md),
and the [debugger API blocker](docs/debugger-blocker.md).

The first experiment, `spikes/bun-repl/probe.mjs`, passed on Bun 1.4.0.
API investigation continues with `scripts/inspect-bun-api.ps1`.

## Build and test

Requires Java 25 and Bun 1.4+. The wrapper pins Gradle 9.3.1.

```powershell
$env:JAVA_HOME = 'C:\path\to\WebStorm\jbr'
.\gradlew.bat -PwebstormPath="C:\path\to\WebStorm" test buildPlugin verifyPlugin
bun test .\tests\runtime\bootstrap.test.mjs
```

Without `webstormPath`, Gradle resolves WebStorm 2026.2.3 from JetBrains.
The plugin archive is written under `build/distributions/`.

The `LoadingState / COMPONENTS_LOADED` startup error reported on this WebStorm
build was reproduced without JS Console when restoring a project. See the
[diagnostic evidence and workaround](docs/webstorm-startup-issue.md).

## Use in a test IDE

`gradlew runIde` uses a separate profile under `build/isolated-ide`.
Open JS Console from the bottom tool-window stripe. By default, Enter adds a
newline and Ctrl+Enter runs input. Under **Settings → Tools → JS Console**, you
can instead choose Enter to run and Shift+Enter to add a newline. The selected
mode takes effect in open consoles immediately; when Enter runs, Ctrl+Enter is
disabled for that console. A completion popup retains its normal Enter behavior.
Up/Down at the first/last visual line (or Alt+Up/Down) browse history.
Commands live in the tool window's native **⋮** menu: Run Input, Restart Runtime,
Clear Output, Pin File Context, and JS Console Settings. Pin is a checked menu
item. The file following the editor and each pinned file appear as native tabs in the
window header, for example `[b.ts · follows editor] [a.ts · pinned]`;
pinning the following file adds `· pinned` to its existing tab. If basenames
match, each matching tab expands to the shortest unique `folder/file` path.
Selecting any tab keeps
the same console input, transcript, and Bun session. Runtime startup and errors
are reported in the transcript. There is
no separate button row or large Run button. The run shortcut can be changed under
Settings → Keymap → Run JS Console Input and is active only inside this console.
Down past the newest command restores the unfinished draft. When completion is
open, arrows navigate its suggestions. Input uses the IDE editor font, and
submitted commands retain JavaScript syntax coloring in the output log.
Editing a recalled command updates its history entry when navigating away or
executing it; the output transcript still records what was actually executed.
The multiline input scrolls with the mouse wheel when its text exceeds the
visible area. Drag the divider above it to change its height. Top-level
`const` and `let` declarations can be repeated in later console commands:
the most recent value is then available by its short name. A duplicate
declaration within the same command remains an error. If the short name came
from a context file, its original export remains under `globalThis['file.ts']`.
Declarations inside functions and blocks keep ordinary JavaScript semantics.
Input edits refresh semantic highlighting of the whole expression, including
earlier methods in a call chain.
Submitted commands keep the input's available semantic colors (methods, globals,
parameters); result strings/numbers receive lexical coloring. Clear empties the
output transcript without resetting variables, command history, or current input.
Completion of loaded file exports inserts bare names, with no ES module import.
Suggestions for other project-file exports are hidden in the console input,
because WebStorm would insert an `import` that the running REPL cannot execute.
If a context file changes after it was loaded, switching to the console input
quietly saves and reimports that file. Its new exports become available without
resetting console variables. If a loaded dependency changes, the header shows a
native **Restart Runtime** action, since reimporting only the parent file would
leave that dependency cached. A failed reimport also offers Restart. Changes are
applied when you return to the console, not on every keystroke in the source file.
The current saved JS/TS file's named exports become available by name. Pin keeps
that file alongside the one following the editor when browsing; Restart resets
Bun and restores both kinds of file context.
History stays visible; previous expressions are never replayed.

Use **Add File to JS Console** on a JS/TS file in the editor or Project tree to
keep that file alongside the main context. Its exports are injected as bare
names, remain available when the main context follows another editor tab, and
return after Restart. The following editor file owns short names when two files
export the same name. Both full export namespaces are also available through
`globalThis['a.ts']` and `globalThis['b.ts']`; when filenames match, use the
same unique `folder/file` keys shown on the tabs. Name collisions in pinned
files also receive aliases (`shared_2`, etc.);
a default export is named from the file (`a_default` for `a.ts`). Use **Remove
File from JS Console** on the same file to remove that added layer. Pin applies
to the file following the editor. The same command in the file context menu can
add a file that has not been opened. Each file has one context tab, but all tabs display the same
console session. Importing either the main or an added file executes its module
top-level code with normal Bun semantics.

In a JS/TS editor, put the caret on an exported function or variable and choose
**Add Symbol to JS Console** from the editor context menu. References to those
declarations, named default exports, and declarations exposed through
`export { local as publicName }` work too. It opens the
console and imports that one export into the persistent session, even when another
file is the current context. Existing globals and console names are preserved:
if `process` is taken, the symbol is added as `process_2`. Added symbols survive a
manual runtime Restart without replaying console expressions. A module-local
declaration such as `const testHighlight` is visible to the editor's PSI/AST,
but cannot be imported as a live value until the module exports it. Running Bun
under a debugger can expose such values in a paused stack frame where they are
in scope; starting Bun with `--inspect` alone does not add them to the REPL.

Choose Bun under **Settings → Tools → JS Console**. Automatic detection searches
PATH, then `~/.bun/bin`; disable it to browse for an executable or enter an absolute
path. The choice is saved in this IDE's local settings for all projects and is not
synced to other computers. Applying settings keeps the current console running;
use **⋮ → Restart Runtime** when ready to switch runtimes. Bun 1.4+ is required.
Static imports support default, named/aliased, namespace and side-effect forms:

```javascript
import path from 'path'
path.join(process.cwd(), 'texts')
```

Imported names remain available in subsequent commands until Restart, including
when switching the current file. Relative imports resolve beside the current or
pinned context file, or from the project directory when no context file is loaded.
Bun resolves packages and built-in modules. Repeating the same import is allowed;
conflicting existing names require an alias. Normal globals (`process`, `console`,
etc.) stay protected. REPL-only implicit module aliases such as `fs` and `path`
are omitted so that explicit imports can use those names. Loaded context exports
are still available directly, without an import. Import attributes (`with`) are
not yet supported; dynamic `import()` expressions keep the backend's behavior.

Reading WebStorm's own Bun setting is blocked by its internal service API; the
JS Console setting is separate. A missing runtime error points to the settings page.
Imports execute normal module top-level code. This version provides a console;
it does not yet attach a debugger or expose non-exported declarations.

Independent Bun processes, builds, offline inspection, network research, and
isolated test IDEs are authorized. Ask before changing the user's working IDE,
installing into its profile, attaching to its process, or restarting it. Keep
test config/system/plugin directories separate from the working IDE profile.

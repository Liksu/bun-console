# JS Console for WebStorm

A WebStorm plugin project intended to provide a persistent Bun console with
direct access to the current file's exports and integrated debugging.

Requirements and acceptance criteria: [handout](CODEX_HANDOUT_JS_CONSOLE.md).
Publishing notes: [marketplace.md](docs/marketplace.md).

Current stage: **console with additional file contexts and experimental Bun debugger integration**.
Automated checks pass; interactive acceptance in a sandbox IDE is still pending.
See [findings](docs/phase-0-findings.md), [acceptance checks](docs/acceptance.md),
and the [debugger integration notes](docs/debugger-blocker.md).

The first experiment, `spikes/bun-repl/probe.mjs`, passed on Bun 1.4.0.
API investigation continues with `scripts/inspect-bun-api.ps1`.

## Build and test

Requires Java 25, Bun 1.4+, and a local Node.js interpreter for the bundled Bun debug adapter. The wrapper pins Gradle 9.3.1.

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
A compact **Run** button beside the input shows the current execution shortcut.
Commands live in the tool window's native **⋮** menu: Run Input, Restart Runtime,
Clear Output, Pin File Context, and JS Console Settings. Pin is a checked menu
item. Every context file has one native tab: pinned files come first, and an
unpinned file following the editor is appended last. The active file's tab is
selected; returning to a pinned file selects its existing tab without a second
`follows editor` tab. If basenames match, each matching tab expands to the
shortest unique `folder/file` path. Selecting any tab keeps the same console
input, transcript, and Bun session. Runtime startup and errors are reported in
the transcript. The run shortcut can be changed under
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
resetting console variables. When the edited file has an enabled breakpoint,
the console instead restarts only its Bun runtime before the next command so the
breakpoint stays bound to Bun's newly compiled TypeScript. The transcript,
history, input and file contexts remain; runtime variables reset. If a loaded
dependency changes, the header shows a native **Restart Runtime** action, since
reimporting only the parent file would
leave that dependency cached. A failed reimport also offers Restart. Changes are
applied when you return to the console, not on every keystroke in the source file.
The current saved JS/TS file's named exports and its other top-level functions,
classes, variables and enums become available by name, exported or not, as in
the DevTools console. Pin keeps
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
console session. Selecting or adding a file does not run it: the file is read
statically, and its module top-level code runs (with normal Bun semantics) the
first time console code reads one of its names or explicitly imports it.
Non-exported declarations are exposed by appending an `export { … }` list after
the module's last line when the console loads it, so line numbers, stack traces
and breakpoints are unchanged. The same is prepared for the project files the
context file imports, so their names are available when you open them later. If
a file was loaded some other way first (for example a dynamic `import()`), its
non-exported names explain that they need Restart Runtime. In CommonJS files
only names in `module.exports` work; reading another top-level name says so.

In a JS/TS editor, put the caret on an exported function or variable and choose
**Add Symbol to JS Console** from the editor context menu. References to those
declarations, named default exports, and declarations exposed through
`export { local as publicName }` work too. It opens the
console and imports that one export into the persistent session, even when another
file is the current context. Existing globals and console names are preserved:
if `process` is taken, the symbol is added as `process_2`. Added symbols survive a
manual runtime Restart without replaying console expressions. Top-level
declarations can be added whether or not they are exported. Declarations nested
inside functions or objects are reachable only in a paused debugger frame where
they are in scope.

Errors never stop the runtime: an error that escapes console code (a rejected
promise nobody awaits, an exception thrown from a timer) is printed as
`Uncaught …` / `Uncaught (in promise) …` and all variables remain. As in
DevTools, a command waiting on `await` does not block later commands; each
result is printed with its command number when it arrives. After 100 ms the
header shows `running…`. If synchronous code occupies Bun's JavaScript thread
(for example `while (true) {}`), the header reports that JavaScript is busy and
offers Restart Runtime, the only way to stop it. Restart also ends processes
that console code started. Console code and project modules share one global
object, so `instanceof Array`, `globalThis` values and classes work across them.
`console.log` and other output of console code appear in order, before the
command's result.

When any JavaScript program you are debugging in this project is stopped at a
breakpoint (for example a Node.js or Bun server started with WebStorm's Debug),
the console behaves like DevTools on a paused page: input is evaluated in the
selected stack frame of that session, and the context tab shows
`paused in <session> at file:line`. Continue/Step in the **⋮** menu control that
session. After it resumes, input returns to the console's own Bun runtime.
Top-level `const`, `let` and `class` declarations can be repeated in later
commands.

Choose Bun under **Settings → Tools → JS Console**. Automatic detection uses the
IDE's PATH lookup (which includes the login-shell PATH on macOS), then
`$BUN_INSTALL/bin`, `~/.bun/bin` and the Homebrew/`/usr/local` locations; disable
it to browse for an executable or enter an absolute path. The choice is saved in
this IDE's local settings for all projects and is not synced to other computers.
Bun 1.4+ is required. The debugger is off by default. Turn it on or off with the
**Debugger** toggle in the console's title bar or **⋮** menu: the runtime restarts
at once (variables reset; transcript, history and file contexts remain). The same
choice appears in Settings, where it applies at the next Restart Runtime.
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
Imports execute normal module top-level code. When the **Debugger** toggle is on,
the console attaches WebStorm's Bun debugger to its Bun process. Locals of a
function are available in a paused stack frame where JavaScript scope exposes
them. The debugger initializes
in the background: after its temporary Debug window is hidden, JS Console is
shown and activated explicitly; WebStorm opens Debug again when execution stops
at a breakpoint. While paused, you can switch back to JS
Console and evaluate expressions against the selected stack frame. Stopping the
Debug session detaches only the debugger: the console and its Bun runtime remain
available without breakpoints. **Restart Runtime** reconnects the debugger.
In the default console-only mode no Inspector or DAP session is created.

Independent Bun processes, builds, offline inspection, network research, and
isolated test IDEs are authorized. Ask before changing the user's working IDE,
installing into its profile, attaching to its process, or restarting it. Keep
test config/system/plugin directories separate from the working IDE profile.

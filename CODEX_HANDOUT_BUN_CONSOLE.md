# Codex Handout: WebStorm “Bun Console” plugin

## Goal

Build a WebStorm plugin that feels like **Chrome DevTools Console for the current JS/TS project**.

Primary UX:

1. A small `>_` tool-window button is always available in WebStorm.
2. Clicking it opens a console immediately.
3. The console can execute arbitrary JavaScript snippets (`reduce`, dates, regexes, `fetch`, promises, etc.).
4. The active JS/TS file is automatically loaded as the console context. Its **exported** names are callable directly, with no prefix and no manual import:

```ts
// parser.ts
export function parse(s: string) { ... }
export function tokenize(s: string) { ... }
```

Console:

```js
> parse("abc")
> tokenize("abc")
```

5. The console session is debug-capable from the start. If a normal WebStorm breakpoint exists inside a called function, invoking that function from the console stops there without the user entering a separate “debug mode”.
6. While paused, the **same console input** evaluates in the selected debugger stack frame, so locals/arguments/closures are available, DevTools-style.
7. Later: `Add Symbol to Bun Console` on a symbol under the caret imports/exposes that symbol in the console.

The plugin must not create scratch/test/playground files in the user's project.

---

# Hard constraints

## No private/dirty JetBrains API

Do **not** use:

- `@ApiStatus.Internal`
- `@IntellijInternalApi`
- reflection to reach private debugger internals
- classes only because they happen to be visible in an `impl` package
- obsolete APIs for new code
- source rewriting/instrumentation to expose private functions

Avoid `@ApiStatus.Experimental` unless there is no stable alternative; if an Experimental API appears necessary, stop and document the exact reason before using it.

Run Plugin Verifier and IntelliJ unstable/internal API inspections before calling a phase complete.

Public XDebugger APIs such as `XDebugSession`, `XDebugSessionListener`, `XStackFrame.getEvaluator()`, and `XDebuggerEvaluator` are acceptable.

## No custom debugger implementation

Do not implement WebKit Inspector Protocol, DAP, breakpoint synchronization, stepping, source maps, or variable scopes ourselves.

Use JetBrains' existing Bun/JavaScript debugger integration. Our plugin orchestrates it.

## No user-project helper files

A small runtime bootstrap shipped as a plugin resource is allowed. If Bun needs a physical file, materialize it under the IDE/plugin system/cache directory, never inside the user's project.

## Only expose legitimately importable symbols

Automatic current-file context exposes **module exports**. Do not rewrite source to expose non-exported lexical declarations.

If a top-level function is not exported, it is not callable from the running console. It becomes available naturally when execution is paused in a debugger frame where it is in scope.

---

# Target

Initial target: **WebStorm 2026.2+**.

Implementation language: Kotlin.

Build system: IntelliJ Platform Gradle Plugin 2.x.

For IntelliJ Platform 2026.2+, use the required Java/JDK level (JetBrains documents Java 25 for 2026.2+).

Primary runtime: **Bun**.

The plugin should use WebStorm's configured Bun runtime if possible. If no Bun runtime is configured/found, show a concise actionable error and optionally allow selecting a Bun executable.

Do not silently fall back to Node in v1 unless designed explicitly later.

---

# Why Bun

Bun is a good fit because:

- it starts quickly;
- it runs `.js`, `.jsx`, `.ts`, `.tsx`, `.mts`, `.cts` directly;
- it has Node compatibility including `node:repl` / interactive mode;
- it has an inspector/debugger and WebStorm 2026.2 has official Bun run/debug support;
- Bun's native REPL supports TS/JSX, top-level await, imports, history and completion, though the plugin does **not** have to parse or embed the native `bun repl` terminal UI.

MVP console input may be JavaScript syntax even when the loaded project file is TypeScript. Loading/debugging TypeScript files is required. Supporting TypeScript syntax typed directly into the console is desirable but secondary; do not complicate the architecture merely to support type annotations in console snippets.

---

# Product model

There is one visible concept: **Bun Console**.

Do not expose “Run mode” vs “Debug mode” as primary user concepts.

Internally the console has two evaluation backends:

```text
RUNNING
  console input
      -> persistent Bun REPL/runtime context

PAUSED AT BREAKPOINT
  console input
      -> XDebugSession.currentStackFrame.getEvaluator()
```

The UI stays the same.

When execution resumes, input automatically routes back to the persistent Bun console context.

---

# Desired UI

Tool window stripe button/icon: approximately `>_`.

Tool window:

```text
┌─ Bun Console ────────────────────────────────┐
│ parser.ts                     follows editor│
│                                             │
│ > parse("12/31/2026")                      │
│ { ... }                                     │
│                                             │
│ > [1,2,3].reduce((a,b) => a+b, 0)          │
│ 6                                           │
│                                             │
│ >                                           │
└─────────────────────────────────────────────┘
```

When paused:

```text
┌─ Bun Console ───── PAUSED · parser.ts:42 ────┐
│ [Continue] [Over] [Into] [Out]              │
│                                              │
│ > normalized                                │
│ "12/31/2026"                                │
│                                              │
│ > options.strict                            │
│ true                                         │
└──────────────────────────────────────────────┘
```

Toolbar eventually:

- Restart runtime
- Pin/unpin current file context
- Add Symbol
- Continue / Step Over / Step Into / Step Out while paused
- Open full WebStorm Debug tool window (optional convenience)

Do not require a run configuration dialog for normal use.

---

# UI implementation rules

Use a normal `com.intellij.toolWindow` extension.

Prefer stable platform components:

- `EditorTextField` (or a stable equivalent) for input, with JavaScript language/file context so WebStorm completion/highlighting can work.
- a stable `ConsoleView` or editor-based read-only output component for history/output.

Do **not** base the design on `LanguageConsoleBuilder` without explicit approval: in the current IntelliJ source it is annotated `@ApiStatus.Experimental`.

The tool window must be lazy: opening a project must not eagerly start Bun.

On first opening Bun Console, show the UI immediately, then start the backend asynchronously. Queue the first submitted expression if startup is still finishing.

Keep the backend alive per project so reopening the tool window is instant.

---

# Runtime architecture

## Preferred approach: plugin-internal Bun bootstrap

Do not treat `bun repl` terminal output as a protocol if avoidable.

Ship a tiny bootstrap JS/MJS file in plugin resources and materialize it to the IDE/plugin cache/system directory on first use.

Run that bootstrap under Bun.

The bootstrap should create a persistent REPL context using Bun's Node compatibility (`node:repl`) or another **documented** Bun/Node-compatible mechanism.

Important properties:

- persistent variables between evaluations;
- arbitrary ordinary JS execution;
- promises/async results handled sensibly;
- programmatic ability to add/remove bindings in the REPL context;
- no user-project file creation;
- deterministic machine-readable control commands separate from user-visible output.

A promising design to validate:

```js
import repl from "node:repl";

const r = repl.start({
  prompt: "",
  terminal: false,
  useGlobal: false,
});

// The plugin can inject exports into r.context.
```

Do not assume this is correct without a spike. Verify it on the Bun version available locally.

If `node:repl` under Bun cannot provide a robust pipe-driven persistent session, document the result and evaluate the next clean option. Do not immediately switch to terminal scraping or private IDE APIs.

## Hidden control channel

The plugin needs operations such as:

- load active file;
- clear previous file-context exports;
- add a named symbol/module export;
- report injected names/collisions;
- restart/shutdown.

Prefer an explicit lightweight protocol rather than printing hidden JavaScript commands into the visible console.

Options, in preference order:

1. a dedicated local IPC/socket/control channel owned by the bootstrap;
2. a clearly framed JSON-lines protocol on a separate stream if feasible;
3. hidden REPL commands defined by the bootstrap (`replServer.defineCommand`) if robust.

Avoid parsing prompts/ANSI formatting from a human-oriented REPL as core protocol logic.

---

# Current-file context

When the Bun Console is opened, determine the active editor file.

Eligible extensions initially:

- `.js`
- `.mjs`
- `.cjs`
- `.jsx`
- `.ts`
- `.mts`
- `.cts`
- `.tsx`

Before loading/reloading a modified active document, save that document so Bun imports the same source the user sees.

Load the module through Bun dynamic import.

For every runtime module export:

- if the export name is a valid identifier;
- and it does not collide with a protected runtime/global name;
- expose it directly in the REPL context.

Example:

```ts
export function parse() {}
export const schema = {};
```

becomes directly callable as:

```js
parse()
schema
```

The user should not need `$file.parse()`.

Internally it is fine to keep metadata/namespace objects under a private unlikely-to-collide name, but do not make that the normal UX.

## Collision policy

Never silently overwrite Bun/JS globals or user-created console bindings.

Track bindings by origin:

```text
builtin/global
user REPL declaration
current-file export
added symbol
```

When changing current file, remove only bindings previously injected by the current-file context layer. Do not remove user-created bindings or manually added symbols.

If an export collides, skip it and show a small non-blocking status such as:

```text
Loaded parser.ts: 7 exports, 1 collision (process)
```

Later allow `Add as alias…`.

---

# Following the editor

Default behavior: context follows the selected editor file.

Use public file-editor/editor selection listeners.

When the active JS/TS file changes:

1. save it if needed;
2. unload the previous auto-context bindings;
3. import the new module;
4. inject its exports;
5. update the context label.

Provide a Pin action so the console can stay bound to one file while the user browses other files.

Do not eagerly auto-import every file in the project.

Important: importing a module executes its top-level code. This is inherent JavaScript semantics. The runtime is isolated in its own Bun process, but external side effects (network/files/DB) remain possible. Do not pretend imports are side-effect-free.

---

# Reload semantics

Do not build a home-grown HMR/module graph invalidator in v1.

A clean **Restart Runtime** should:

1. stop the Bun process/debug session;
2. start a fresh process;
3. reattach/recreate the debugger session;
4. restore pinned/current file context;
5. re-add manually added symbols;
6. keep console text/history in the IDE UI.

Do **not** replay arbitrary user expressions automatically after restart because they may have side effects.

Optional later feature: restart-on-save.

Start conservative: manual restart button/shortcut first.

---

# Always-on debugging

This is the key technical spike.

The desired experience is:

```text
open Bun Console
  -> Bun backend starts
  -> WebStorm JavaScript/Bun debugger is already attached/associated
  -> no Debug tool window is forced in front of the user

set normal WebStorm breakpoint
call parse() in Bun Console
  -> breakpoint is hit immediately
```

Use JetBrains' **existing Bun/JavaScript debugger integration**.

Relevant evidence from current JetBrains distribution:

- WebStorm 2026.2 officially runs/debugs JS/TS through Bun.
- JetBrains' Bun plugin declares dependencies on `JavaScriptDebugger`, `JavaScript`, `NodeJS`, `intellij.platform.dap`, and `intellij.javascript.debugger.dap.launcher`.
- `XDebugSessionBuilder` is public/non-extendable and supports starting a session without automatically showing a tab; it also has `showToolWindowOnSuspendOnly` for sessions that do show a tab.

However, **do not assume a particular Bun debugger launcher class is public**.

## Phase 0 debugger API investigation

Before implementing debugger integration:

1. Build the plugin against the exact local WebStorm 2026.2.x installation or matching Gradle artifact.
2. Add only declared plugin/module dependencies.
3. Inspect the Bun plugin and JavaScript Debugger plugin APIs as a consumer (attached sources/decompiled signatures are fine).
4. Find the supported/public path used to start a Bun debug run/session programmatically.
5. Check every candidate class/method for `ApiStatus` annotations.
6. Run Plugin Verifier/IDE inspections.

Preferred solutions:

A. Programmatically create/use the standard Bun run/debug configuration supplied by JetBrains and execute it with the Debug executor.

B. Use a documented/public DAP-launcher API exposed by the JavaScript debugger module if one exists for third-party plugins.

Do not use reflection or internal launcher classes.

If no stable public Bun-debug launch API exists, implement the console-only milestone cleanly and produce a short blocker report. Do not sneak in internal APIs.

---

# Debugger/session integration after a public launch path is proven

Keep a reference to the resulting `XDebugSession` associated with this Bun Console backend.

Listen with public `XDebugSessionListener`:

- `sessionPaused()`
- `sessionResumed()`
- `sessionStopped()`
- `stackFrameChanged()`

Use public `XDebugSession` control methods:

- `resume()`
- `stepOver(false)`
- `stepInto()`
- `stepOut()`
- `getCurrentStackFrame()`
- `getCurrentPosition()`

When paused, route Bun Console input to:

```kotlin
val frame = session.currentStackFrame
val evaluator = frame?.evaluator
```

and call public `XDebuggerEvaluator.evaluate(...)`.

That is how the console gains the selected frame's locals/arguments/closures without implementing debugger scopes itself.

When the selected stack frame changes, subsequent console input must evaluate in the newly selected frame.

When resumed, route input back to the Bun REPL/runtime backend.

## Result rendering while paused

`XDebuggerEvaluator` returns an `XValue` asynchronously. Render a useful textual value using stable public XDebugger presentation/value APIs. Do not depend on Debug tool-window implementation classes.

MVP result rendering may be simpler than the full native variable tree, but must show a meaningful type/value/error.

---

# Important asynchronous behavior

Example:

```js
> parse("foo")
```

The Bun REPL call may enter user code and stop at a breakpoint before returning.

The plugin UI must not block the EDT while waiting for that command.

Expected UX:

```text
> parse("foo")
  [paused at parser.ts:42]

> localVariable
  "value"
```

After Continue, the original `parse("foo")` evaluation may complete and its result can be appended to the corresponding command entry.

Design evaluation jobs/command IDs so output can be correlated without freezing input or the IDE.

---

# Add Symbol to Bun Console

This is post-MVP but architect for it now.

Action should be available from editor intention/context menu when caret/selection resolves to an importable JS/TS symbol.

Desired behavior:

```ts
// somewhere in project
export function calculatePrice(...) {}
```

Caret on `calculatePrice` -> `Add Symbol to Bun Console` -> console can immediately call:

```js
calculatePrice(...)
```

Implementation outline:

1. Use WebStorm JavaScript PSI/reference resolution (public APIs from the `JavaScript` plugin) to resolve the symbol.
2. Determine its source module and exported name.
3. Ask runtime bootstrap to import that module and bind that export into the persistent console context.
4. Persist manually-added symbols across automatic current-file changes.
5. Restore them after runtime restart.

Initial support can be restricted to named/default exports that can be imported without source transformation.

For a non-exported local declaration, disable the action or explain that it is not importable; do not rewrite the source.

Support aliasing later for collisions.

---

# Completion

The input field should feel like a code editor, not a terminal text box.

MVP:

- JavaScript syntax highlighting;
- standard JS code completion for globals/language APIs;
- history Up/Down;
- multiline editing;
- Ctrl+Enter (or configurable shortcut) executes;
- ordinary Enter inserts newline or use a clear console convention; choose ergonomics deliberately.

Then augment completion with runtime-injected names:

- current file exports;
- manually added symbols.

Prefer feeding symbol metadata into a completion contributor/context visible only to the Bun Console input editor, rather than globally modifying JavaScript predefined libraries for the whole project.

Do not block MVP on perfect runtime property completion.

---

# Project/package context

Do not auto-import an entire project tree.

Possible later feature: load nearest package public entry (`package.json` `exports` / `main`) and expose it as additional context. This is not MVP.

The main automatic context is the **current file**.

---

# Architecture suggestion

Suggested package layout (adjust names as needed):

```text
com.example.bunconsole
  ui/
    BunConsoleToolWindowFactory.kt
    BunConsolePanel.kt
    ConsoleInput.kt
    ConsoleOutput.kt

  service/
    BunConsoleProjectService.kt
    ConsoleContextManager.kt
    ConsoleHistory.kt

  runtime/
    BunRuntimeLocator.kt
    BunConsoleProcess.kt
    BootstrapManager.kt
    RuntimeProtocol.kt
    RuntimeBinding.kt

  debug/
    DebugSessionBridge.kt
    PausedFrameEvaluator.kt

  context/
    ActiveFileContextProvider.kt
    ExportBindingManager.kt
    AddedSymbolManager.kt

  actions/
    RestartConsoleAction.kt
    PinContextAction.kt
    AddSymbolToConsoleAction.kt
```

`BunConsoleProjectService` owns lifecycle/state per project.

It should be the one source of truth for:

```text
backend state: stopped / starting / running / paused / failed
active context file
pin state
injected current-file bindings
manually added symbol bindings
XDebugSession if present
pending evaluations
```

---

# Suggested development phases

## Phase 0 — API viability spike

Do not build UI polish yet.

Prove and document:

1. plugin skeleton runs in WebStorm 2026.2;
2. no internal/experimental API violations in chosen core path;
3. Bun executable can be located using WebStorm settings or a clean fallback;
4. plugin-internal bootstrap can be launched under Bun;
5. persistent REPL context works over pipes;
6. a `.ts` project module can be imported and its export placed in REPL context;
7. identify a fully public route to start/attach WebStorm's Bun debugger to this process.

If item 7 fails on public API, stop debugger work and report exact API limitation.

Deliver `docs/phase-0-findings.md` with class/API names and ApiStatus findings.

## Phase 1 — useful instant console

Implement:

- tool window stripe button;
- lazy Bun process startup;
- input/output/history;
- arbitrary JS execution;
- current file auto-load;
- exported functions directly callable;
- file-follow and pin;
- manual restart;
- no debugger yet if Phase 0 debugger path is unresolved.

This phase must already be genuinely useful for quick `reduce/date/regex/fetch` experiments.

## Phase 2 — always-on debugger

Only after Phase 0 proves a public integration path:

- launch console backend under normal JetBrains Bun debugger integration;
- normal WebStorm breakpoints work;
- session does not unnecessarily steal focus/show Debug UI on normal console use;
- pause/resume state reflected in Bun Console;
- paused input uses `XDebuggerEvaluator` of selected frame;
- Continue/Over/Into/Out toolbar controls.

## Phase 3 — Add Symbol to Bun Console

- action under caret;
- resolve exported declaration through JS PSI;
- import/bind at runtime;
- persist list across file-context switches and runtime restart;
- collision/alias handling.

## Phase 4 — polish

- richer object rendering;
- context-aware completion for injected names;
- restart-on-save option;
- optional package API context;
- settings/keybindings;
- Marketplace metadata/tests.

---

# Tests / acceptance checks

At minimum test manually and, where practical, automate these scenarios.

## Plain JS scratch use

```js
[1,2,3].reduce((a,b) => a+b, 0)
new Date("2026-09-23").toISOString()
/foo-(\d+)/.exec("foo-42")
await fetch("https://example.com")
```

No file creation required.

## Current TypeScript file

```ts
export function twice(x: number) {
  return x * 2;
}
```

Open file -> open Bun Console -> `twice(21)` returns `42` without manual import/prefix.

## Switch files

File A exports `a`, file B exports `b`.

Follow-editor mode:

- A selected => `a()` works;
- switch to B => injected A binding is removed, `b()` works;
- manually entered console variables remain.

## Collision

File exports `process`.

Do not overwrite Bun/Node global `process`; report collision.

## Pin

Pin A, browse B: A context remains active.

## Restart

Restart backend:

- process state is fresh;
- console history remains visible;
- current/pinned file is restored;
- manually added symbols are restored;
- arbitrary user expressions are not replayed.

## Breakpoint

With breakpoint inside exported `twice`:

```js
twice(21)
```

must stop at normal WebStorm breakpoint without user starting a second debug flow.

## Paused frame console

At breakpoint:

```ts
export function twice(x: number) {
  const y = x * 2;
  // breakpoint
  return y;
}
```

Console while paused:

```js
x
// 21

y
// 42
```

Continue returns console to runtime/global mode.

## Threading

No evaluation, import, process startup, debugger attach, or wait may block the EDT.

---

# Quality gates

Before each milestone:

- build succeeds;
- tests pass;
- `verifyPlugin` / Plugin Verifier passes for target WebStorm build(s);
- no use of `@ApiStatus.Internal` or `@IntellijInternalApi`;
- no reflection workaround;
- no files created in the user's project;
- no source rewriting;
- no custom debugger protocol implementation.

For 2026.2, respect JetBrains' newer debugger frontend/backend architecture. Do not use obsolete UI access such as `XDebugSession.getUI()` for new code.

---

# Key public APIs already verified as relevant

These are worth inspecting first:

```text
com.intellij.xdebugger.XDebugSession
com.intellij.xdebugger.XDebugSessionListener
com.intellij.xdebugger.frame.XStackFrame
com.intellij.xdebugger.evaluation.XDebuggerEvaluator
com.intellij.xdebugger.XDebugSessionBuilder
```

Current public API facts:

- `XDebugSession.getCurrentStackFrame()` returns selected frame.
- `XStackFrame.getEvaluator()` is the supported evaluator entry for watches/Evaluate/conditions.
- `XDebuggerEvaluator.evaluate(...)` is public async evaluation API.
- `XDebugSession` exposes resume/step methods.
- `XDebugSessionListener` exposes pause/resume/stop/frame-change events.
- `XDebugSessionBuilder` defaults to not showing a debug session tab; it also exposes `showToolWindowOnSuspendOnly` when a tab is used.

Do not confuse “public XDebugger API exists” with “public Bun-launcher API definitely exists”; Phase 0 must prove the latter.

---

# References researched (September 2026)

JetBrains:

- WebStorm Bun support: https://www.jetbrains.com/help/webstorm/bun.html
- IntelliJ Platform WebStorm plugin development: https://plugins.jetbrains.com/docs/intellij/webstorm.html
- Run configurations: https://plugins.jetbrains.com/docs/intellij/run-configurations.html
- Execution workflow: https://plugins.jetbrains.com/docs/intellij/run-configuration-execution.html
- Plugin compatibility/verifier: https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html
- WebStorm extension points: https://plugins.jetbrains.com/docs/intellij/webstorm-extension-point-list.html
- JetBrains Bun plugin Marketplace page: https://plugins.jetbrains.com/plugin/25668-bun/versions
- JavaScript Debugger Marketplace page: https://plugins.jetbrains.com/plugin/17562-javascript-debugger/versions/stable
- XDebugSession source: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSession.java
- XStackFrame source: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/frame/XStackFrame.java
- XDebuggerEvaluator source: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/evaluation/XDebuggerEvaluator.java
- XDebugSessionListener source: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSessionListener.java
- XDebugSessionBuilder source: https://github.com/JetBrains/intellij-community/blob/master/platform/xdebugger-api/src/com/intellij/xdebugger/XDebugSessionBuilder.kt

Bun:

- REPL: https://bun.sh/docs/runtime/repl
- Debugger: https://bun.sh/docs/runtime/debugger
- TypeScript: https://bun.sh/docs/runtime/typescript
- Runtime/CLI flags: https://bun.sh/docs/runtime
- File loaders: https://bun.sh/docs/runtime/file-types

---

# Instructions to Codex

Start with **Phase 0** and keep a short engineering log in `docs/phase-0-findings.md`.

Do not guess JetBrains API names. Inspect the actual 2026.2 dependency artifacts/sources available in the project.

When evaluating a JetBrains API, record:

```text
class/method
source plugin/module
ApiStatus annotation
why it is needed
whether Plugin Verifier accepts it
```

If a clean public debugger-launch route is found, continue through Phase 1 and Phase 2.

If it is not found, complete Phase 1 console functionality cleanly and stop before adding a debugger hack. Report the exact blocker and the most plausible upstream/public-API request.

Optimize for the UX described above, not for architectural ceremony. The core test is: **click `>_`, type code immediately, call exports from the current file by name, and if a breakpoint is set, hit it naturally.**

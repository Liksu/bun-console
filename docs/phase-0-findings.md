# Phase 0 — engineering log

Status: IN PROGRESS. The console and Experimental DAP debugger integration
pass isolated automated tests. The earlier API blocker and its bounded exception
are documented in `debugger-blocker.md`. Interactive sandbox acceptance remains
pending; phases are not declared fully accepted yet.

Current authorization: independent processes, isolated tests/builds/IDEs, and
network research are allowed without another question. Ask before an action that
could affect the user's working IDE or its settings, plugins, process, or work.

## 2026-09-23: read-only inventory

- Workspace initially contained only `CODEX_HANDOUT_JS_CONSOLE.md`.
- Running IDE executable: `C:/Users/Petro/AppData/Local/Programs/WebStorm/bin/webstorm64.exe`.
- Installed `product-info.json`: WebStorm **2026.2.3**, **WS-262.10968.77**,
  minimum Java **25**. This is the exact initial target, not an assumed version.
- Bundled `jbr/release`: Java **25.0.4**, JBR **25.0.4+1-508.27-nomod**.
  `jbr/bin/javac.exe` exists. `javap.exe` was not found at that location.
- Bun executable exists at `C:/Users/Petro/.bun/bin/bun.exe`.
  Its version and compatibility remain untested; do not infer them from its date.
- Gradle **9.3.1** distribution exists in the user's wrapper cache.
- No running IDE settings, plugins, or projects were changed.

## Actual Bun plugin descriptor

Read directly from the ZIP entry `META-INF/plugin.xml` in:

`C:/Users/Petro/AppData/Local/Programs/WebStorm/plugins/javascript-bun/lib/javascript-bun.jar`

Plugin ID: `intellij.javascript.bun`, version `262.10968.77`.

Declared dependencies:

```text
plugin: NodeJS
module: intellij.javascript.backend
module: intellij.javascript.debugger.backend
module: intellij.javascript.debugger.dap.launcher
module: intellij.javascript.psi.impl
module: intellij.json
module: intellij.platform.dap
module: intellij.platform.webide.impl
```

These differ from the handout's simplified dependency list. Our own plugin
must declare only the dependencies required by its verified API usage.
Dependencies of JetBrains' own plugin are not permission to use internal APIs.

The descriptor registers a standard Bun configuration type. This makes the
standard configuration + Debug executor route the first candidate to inspect.
Registration alone does NOT establish a supported third-party launch API.

## API candidates found in the installed artifact

All class names below were read from JAR entries or the descriptor. Method
signatures, enclosing/package annotations, and accessibility still need audit.

| Class / method | Source plugin or module | ApiStatus | Purpose | Plugin Verifier |
| --- | --- | --- | --- | --- |
| `com.intellij.javascript.bun.runConfiguration.run.BunRunConfigurationType.createTemplateConfiguration(Project)` | `intellij.javascript.bun` | No ApiStatus marker; class is Kotlin internal (compiler confirmed) | Find standard configuration factory | Not linked: rejected at compilation |
| `com.intellij.javascript.bun.runConfiguration.run.BunRunConfiguration.setFilePath/setWorkingDirectory/setRuntimeParameters` | `intellij.javascript.bun` | No ApiStatus marker; class is Kotlin internal (compiler confirmed) | Configure bootstrap | Not linked: rejected at compilation |
| `com.intellij.javascript.bun.runConfiguration.run.BunRunConfigurationOptions` | `intellij.javascript.bun` | Kotlin internal metadata | Configure parameters | Not linked |
| `com.intellij.javascript.bun.settings.BunSettingsService` and `Companion.getConfiguredPath(Project)` | `intellij.javascript.bun` | Enclosing service is `ApiStatus.Internal` | Locate configured Bun | Not linked: forbidden |
| `com.intellij.javascript.bun.settings.BunRuntimeProvider` | `intellij.javascript.bun` | Kotlin internal metadata | Alternative discovery | Not linked |
| `com.intellij.javascript.bun.BunDebugAdapterSupportProvider.createDebugAdapterDescriptor(Project)` | `intellij.javascript.bun` | Package-private JVM class | Existing adapter wiring | Not linked: inaccessible |
| `com.intellij.javascript.debugger.dap.launcher.LauncherKt.NodeJsBasedDebugAdapter(...)` | `intellij.javascript.debugger.dap.launcher` | No ApiStatus marker observed | Node-hosted adapter handle, not Bun session launch | Not linked: insufficient integration path |

`intellij.javascript.debugger.dap.launcher` exists in the installed product layout
at `plugins/javascript-debugger/lib/modules/intellij.javascript.debugger.dap.launcher.jar`.
No API in that module has yet been approved for use.

## Prepared runtime experiment

`spikes/bun-repl/probe.mjs` is a disposable feasibility probe, not the production
bootstrap. It starts a bounded child process of the same Bun executable and checks:

- stream-backed `node:repl` construction and evaluator callbacks;
- persistent lexical bindings and ordinary JS;
- importing a local TypeScript fixture and injecting/removing its export;
- lexical shadowing of context properties (a collision-policy hazard);
- top-level await through the chosen evaluator entry point.

The worker writes JSON-lines reports to stdout. This is only the test harness:
the production control channel must remain separate from arbitrary user output.
This experiment does not yet prove command transport, import resolution relative
to a user project, debugger integration, or robust binding ownership.

Fixtures live in the plugin development repository. They are never generated in
a user's project. This experiment makes no network requests and opens no IDE.
Parent timeout is 15 seconds; a timed-out worker is killed and the probe fails.

Executed with user approval (PowerShell, workspace cwd):

```powershell
& 'C:\Users\Petro\.bun\bin\bun.exe' '.\spikes\bun-repl\probe.mjs'
```

## 2026-09-23: runtime experiment result

Runtime: **Bun 1.4.0**. Exit code: **0**. Worker reported **0 failures**.

| Check | Result |
| --- | --- |
| Construct stream-backed `node:repl` with isolated context | PASS |
| Persist `const consoleOwned = 21` across evaluations | PASS |
| `reduce`, date, and regular-expression expressions | PASS |
| Dynamically import TypeScript, inject `twice`, evaluate `twice(21) === 42` | PASS |
| Remove injected `twice` while preserving user declaration | PASS |
| Observe lexical binding versus context property | PASS: lexical declaration is NOT an own property |
| `await Promise.resolve(42)` via evaluator callback | PASS |

Important binding-policy finding: `Object.hasOwn(repl.context, name)` alone
cannot detect a user `const` declaration. In this experiment, injecting a context
property of the same name leaves the lexical declaration shadowing that property.
Production code must detect lexical conflicts and must not falsely report those
exports as successfully exposed. This probe intentionally performs that collision
only in the disposable worker; it is not a proposed production binding algorithm.

The successful evaluator probe supports continuing with `node:repl`. It does not
yet establish robust request transport, promise/error handling in all cases,
source-map breakpoint behavior, or the selected debugger launch route.

## Prepared offline API inspection

Located an existing bytecode viewer without launching it:

`C:/Users/Petro/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin/javap.exe`

`scripts/inspect-bun-api.ps1` is prepared to run this viewer against eight exact
classes in the installed Bun JAR, saving full signatures and annotation output
under `build/phase-0/api/`. It does not launch or attach to WebStorm, execute IDE
classes, build the plugin, or access the network. Compatibility of this existing
JDK 17 viewer with the installed class files is still untested; a reader failure
will not be interpreted as an API finding. There are no `package-info.class` or
`module-info.class` entries in this Bun JAR.

Executed after the user authorized safe independent launches:

```powershell
.\scripts\inspect-bun-api.ps1 -JavapPath 'C:\Users\Petro\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2\bin\javap.exe' -WebStormPath 'C:\Users\Petro\AppData\Local\Programs\WebStorm'
```

## Next steps, in order

1. DONE: run the approved REPL probe and record exact Bun version/results.
2. DONE: inspect installed class signatures and annotations with an offline bytecode tool.
   Never load IDE classes to reach private state via reflection.
3. DONE: build the plugin skeleton with Gradle 9.3.1, Kotlin 2.4.0, IntelliJ
   Platform Gradle Plugin 2.19.0, and the installed Java 25 runtime.
4. Complete platform integration tests and strict verification. Test IDE must
   use separate config/system/plugin directories and a dedicated fixture project.
5. Prove debugger launch, session association, and selected-frame evaluation via
   stable public APIs. If no route exists, record the precise blocker and proceed
   with Phase 1 console only.

Phase 0 is not complete until its runtime and plugin acceptance checks, Plugin
Verifier, and unstable/internal API inspections have actually passed.

## Implementation and validation notes

- Runtime control uses a token-authenticated loopback socket. stdout/stderr remain
  separate visible output, including JSON-looking `console.log` text.
- Native `node:repl` evaluation initially hung on a thrown exception because the
  REPL handled the error without invoking the evaluation callback. The documented
  `repl.start({ handleError })` option resolves it on Bun 1.4.0. Minimum runtime is
  therefore explicitly Bun 1.4+, with sync/async/syntax failure tests.
- Current exports use tracked accessor properties. A user assignment transfers
  ownership to the user, so changing files does not delete that value. Lexical
  conflict detection also checks the VM context, not just object properties.
- First Plugin Verifier run found a reserved word in the plugin ID; changed to
  `dev.jsconsole`. The subsequent run found a deprecated chooser and compiler-
  generated delegates to deprecated/experimental `ToolWindowFactory` methods.
  Use `FileChooserDescriptorFactory.singleFile()` and Kotlin
  `JvmDefaultMode.NO_COMPATIBILITY`; bytecode inspection now shows only the
  constructor and `createToolWindowContent`. No warning suppression is used.
- Negative Kotlin visibility probe is opt-in (`-PapiAudit=true`) and excluded
  from normal builds. It intentionally fails rather than bypassing visibility.
- Platform test fixtures must use real local files. The default in-memory fixture
  cannot be imported by an external Bun process and is intentionally ineligible
  for automatic file context.
- The input uses the documented `LanguageTextField` component. A PSI file created
  without event-system support had no backing document; the language field fixes
  this and provides an ECMAScript 6 document. Its creation and execution action
  are exercised by the platform test.

## Final automated run (2026-09-23)

- `bun test tests/runtime/bootstrap.test.mjs`: **2 tests passed, 20 assertions**.
- `gradlew test buildPlugin verifyPlugin` against the installed WebStorm:
  **BUILD SUCCESSFUL**; **4 JVM/platform tests passed**.
- Plugin Verifier **1.410**, strict `FailureLevel.ALL`: **Compatible** with
  **WS-262.10968.77**; no internal, experimental, deprecated, or override-only
  API findings in the final plugin. The installed IDE's layout metadata produces
  warnings about missing unrelated module paths; these are not hidden.
- Artifact: `build/distributions/js-console-0.1.0-dev.zip` (37,645 bytes).
- Artifact SHA-256: `3D84CE10992263CAF2E6F844C7A8A3B540A37E13ADCA64C27765BB95B65F5E44`.
- Main validation log: `build/phase-0/final-validation.txt`.
- JVM test reports: `build/reports/tests/test/index.html`.
- No plugin was installed into the user's working IDE. All IDE component tests
  used the separate Gradle test sandbox and did not open an interactive IDE window.
- Still pending: actual keyboard/focus/completion interaction and the separate
  IntelliJ JVM source-inspection gate described in `docs/acceptance.md`.

## UI regression fix: 0.1.1-dev (2026-09-23)

The first interactive report showed a proportional input font, plain submitted
commands, and nonfunctional Up history. The field inherited its Swing font, and
its key listener competed with the IDE editor action system.

- Input now uses the configured editor font (`setFontInheritedFromLAF(false)`).
- `EditorUp`/`EditorDown` extension handlers opt in only for console input editors.
  They delegate ordinary editor movement and completion navigation, recall history
  at the first/last visual line, and preserve unfinished draft text.
- Submitted source is recorded separately from normal/error output and colored
  with the JavaScript lexer, including transcript replay after reopening the panel.
- The expanded platform test invokes the registered editor handlers and checks
  history/draft/multiline behavior, completion priority, ordinary file editors,
  the configured font, input syntax colors, and colored transcript replay.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, strict verifier verdict
  **Compatible** against WS-262.10968.77. Log: `build/phase-0/ui-fix-validation.txt`.
- Artifact: `build/distributions/js-console-0.1.1-dev.zip` (46,574 bytes).
  SHA-256: `B2B70ECA22C6D7C0389A47414D8698568B12102A321C59CDB861F32FE88F0F0D`.
- Actual keyboard/focus retesting of this build and the separate JVM source
  inspection gate remain pending. No running IDE was restarted by the agent.

## Edited history and chained-call highlighting: 0.1.2-dev (2026-09-23)

- History now saves edits to the selected entry before moving in either direction
  or executing. The original executed source remains in the output transcript.
- The input's light document had only narrow edit-range invalidation, leaving
  semantic coloring of earlier calls stale. A full analysis of the screenshot's
  Date/toISOString/split/filter/reduce expression correctly colors every call.
- `ConsoleInputHighlighting` requests file-scoped analysis through public
  `DaemonCodeAnalyzer.restart(PsiFile, Object)` after the edited PSI is committed.
  Superseded callbacks and callbacks after disposal are ignored; the daemon
  schedules analysis. No global restart or synchronous analysis runs on typing.
- JetBrains describes the light-document invalidation limitation in
  [DaemonCodeAnalyzer](https://github.com/JetBrains/intellij-community/blob/master/platform/analysis-api/src/com/intellij/codeInsight/daemon/DaemonCodeAnalyzer.java).
  Its `INTERACTIVE_NON_PHYSICAL_DOCUMENT` marker is internal and is NOT used.
- Targeted history and semantic-color tests passed. The color test appends calls
  incrementally, deletes a trailing call, and clears/restores the expression.
  It drives analysis explicitly, so focused-window confirmation remains pending.
- Artifact: `build/distributions/js-console-0.1.2-dev.zip` (49,876 bytes).
  SHA-256: `1F4446FAD081E80ADE49A776F7487AEDD9B2B2C8046539495462B790D922D183`.
- Full validation log: `build/phase-0/chain-validation.txt`.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, **5 JVM/platform tests
  passed**, strict Plugin Verifier verdict **Compatible** against WS-262.10968.77.
  The separate source-inspection gate remains pending as previously documented.

## Console output, startup, completion and controls: 0.1.3-dev (2026-09-23)

- Opening a restored tool window and clicking Restart could save a context
  document from a write-unsafe callback. Saving is now queued through public
  `Application.invokeLater` with `ModalityState.nonModal()`; import/evaluation
  waits for successful saving. Only modified context documents are saved.
- An initial attempt used `WriteIntentReadAction`, as suggested by the runtime
  error. Strict Plugin Verifier identified it as experimental. It was removed;
  explicit non-modal application scheduling passes the startup/Restart tests.
- The transcript previously used only lexical colors. Commands now retain a
  snapshot of the input's available JS semantic color keys before clearing it,
  including method/global/parameter colors. Those keys also survive transcript
  replay. Results use lexical colors; unrelated stdout/error text stays separate.
  If analysis has not yet produced semantic colors, lexical colors remain the
  fallback; execution never waits for semantic analysis.
- Clear erases the visible and retained transcript, preserving input, history,
  runtime variables and file context. In-flight/new command results can still
  appear after clearing, as normal for a live console.
- Console-specific completion supplies currently injected export names and
  removes competing auto-import suggestions for those names. A Tab insertion
  of `lexical` remains `lexical`, and is evaluated successfully in Bun. It does
  not enable static ES module declarations in the REPL or provide imports of
  unrelated modules. No global completion/auto-import settings are changed.
- Icons now use the prescribed light/dark tool-window colors (`#6C707E`,
  `#CED0D6`) and include 16/20-pixel variants. Pin and Bun have explanatory tooltips.
- **6 JVM/platform tests passed** in `build/phase-0/console-fixes-tests.txt`.
  These cover actual Tab completion, semantic color transfer after clearing input,
  result colors, Clear preserving runtime/history/draft, and modified documents
  saved before startup/Restart from `ModalityState.any()` callbacks.
- Build/verifier log: `build/phase-0/console-fixes-validation.txt`.
- Final `buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, strict verdict
  **Compatible** against WS-262.10968.77; no experimental/internal/deprecated API
  findings. Archive `build/distributions/js-console-0.1.3-dev.zip`: 63,362 bytes,
  SHA-256 `28A3EABC8D0C85D3D8966A4FAE9280223278EB3298B0B273ECD0801F7E0F2E60`.
- Physical UI checks (icon states, mouse/keyboard focus) and separate JVM source
  inspections remain pending. The user's working IDE was not modified/restarted.

## Static imports and independent startup diagnosis: 0.1.4-dev (2026-09-24)

- The user's `import path from 'path'` command reached `node:repl` unchanged,
  which rejects static import declarations. `ConsoleImports` now uses public JS
  PSI to separate default/named/aliased/namespace/side-effect imports. The original
  transcript is retained. Empty statements preserve source offsets and statement
  boundaries in executable input; no project source files are rewritten.
- Parsing runs on the service worker under `ReadAction.computeBlocking`. The
  initial `ReadAction.compute` call was rejected by strict Plugin Verifier as
  deprecated and replaced with the current public method.
- Bun resolves import plans relative to the selected/pinned context file, or the
  project cwd in plain mode. Imported names persist between commands and file
  switches; Restart resets them. Existing globals/user values cannot be replaced
  by an import. Repeating the same import is allowed. Live module getters remain
  live, including when a context export is explicitly imported and then the
  current file changes.
- `node:repl`'s implicit module aliases (`path`, `fs`, etc.) are removed initially
  so they do not collide with legitimate explicit imports. Normal Bun/JS globals
  remain. Import bindings are committed only after all module/export checks
  succeed; normal module side effects are not rolled back.
- Import attributes are explicitly rejected. Dynamic import expressions are left
  unchanged, as are strings/comments containing the word `import`.
- A Windows test cleanup race was fixed by awaiting the test child's termination
  before deleting its working directory; `close()` requests asynchronous exit.
- The reported `LoadingState` stack was reproduced **without the plugin** when
  restoring a project in a separate WebStorm profile. See the
  [startup diagnosis](webstorm-startup-issue.md). It remains a platform defect;
  logging/assertions are not suppressed, and the working IDE was not changed.
- Final `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**; **9 JVM/platform
  tests passed**. Strict Plugin Verifier verdict: **Compatible**, with no API
  findings. Log: `build/phase-0/import-validation.txt`.
- Separate Bun runtime suite: **3 tests passed, 37 assertions**. Log:
  `build/phase-0/import-runtime-tests.txt`.
- Archive: `build/distributions/js-console-0.1.4-dev.zip`, **73,585 bytes**.
  SHA-256: `FAF80BA3E4A2A198A205BED8DE20A8EE322C54738029F3481D734D0D3DB058CF`.
- Physical UI acceptance and separate JVM source inspections remain pending;
  these checks do not claim completion of the debugger/full handout scope.

## Runtime settings: 0.1.5-dev (2026-09-24)

- Bun selection moved from the console toolbar into **Settings → Tools →
  JS Console**. Automatic mode uses PATH then `~/.bun/bin`; manual mode accepts
  an absolute path to an executable file. Missing/relative paths are rejected
  before saving. Runtime version validation still happens when Bun starts.
- The application-level persistent service stores the choice in `js-console.xml`
  inside the IDE config directory, with roaming disabled for this machine-specific
  path. No settings files are created in user projects.
- Apply saves the choice without restarting a running console. The next console
  Restart/initial startup reads it. The page explicitly describes this behavior.
  Startup errors now direct the user to the settings page instead of the removed
  Bun toolbar button.
- The platform test verifies real settings UI Apply/Reset/validation, XML state
  round-tripping, unchanged live variables after Apply, and the next runtime using
  the selected setting (including an executable removed after saving).
- Runtime discovery is not uniform across extensions. The official Bun VS Code
  extension has its own `bun.runtime` setting and otherwise launches `bun` from
  PATH: [manifest](https://github.com/oven-sh/bun/blob/main/packages/bun-vscode/package.json),
  [getRuntime implementation](https://github.com/oven-sh/bun/blob/main/packages/bun-vscode/src/features/debug.ts).
- WebStorm's bundled Bun service is internal. An additional check of the generic
  `JSRuntimeProvider` interface also found `@ApiStatus.Internal`; it is not used.
  This is a limitation of the inspected integration routes, not a claim about
  how every third-party WebStorm plugin is implemented.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, **10 JVM/platform tests
  passed**, strict Plugin Verifier verdict **Compatible**. Log:
  `build/phase-0/settings-validation.txt`. Focused settings test log:
  `build/phase-0/settings-tests.txt`.
- Archive: `build/distributions/js-console-0.1.5-dev.zip`, **81,058 bytes**.
  SHA-256: `E00962AD875FCFFC08862234A7E9EAD6B2AE164AD185B7A0B06DC63EDFB08311`.
  Physical UI acceptance and the separate JVM source-inspection gate remain
  pending. No working IDE profile was modified or restarted.

## Native tool-window menu: 0.1.6-dev (2026-09-24)

- The console used ordinary Swing `JButton`s in its own toolbar. WebStorm also
  uses Swing, but its tool-window commands are presented through the IntelliJ
  Action System. The custom button row and oversized Run button were removed.
- Run Input, Restart Runtime, Clear Output, Pin File Context, and JS Console
  Settings are now a `DefaultActionGroup` installed using the public
  `ToolWindow.setAdditionalGearActions` API. The IDE renders them inside its own
  options menu, alongside standard tool-window controls. Pin uses `ToggleAction`
  so the selected state is rendered as a native menu check mark.
- Context/runtime status uses the public tool-window title API. No duplicate
  popup, imitation header, custom menu styling or internal tool-window classes
  were added.
- Run Input is a local `DumbAwareAction` with a disposed/blank-input guard and
  explicit EDT updates. Its shortcut comes from a registered `EmptyAction`
  placeholder, so Ctrl+Enter is available in Keymap and scoped to this panel.
  Shortcut registration is removed with the panel's disposable lifetime.
- The panel regression test now invokes the real menu actions. It checks blank
  Run availability and the assigned shortcut, Clear preserving draft/history/live
  variables, Pin toggling, and Restart creating a fresh runtime.
- The focused test passed (`build/phase-0/menu-tests.txt`). Physical menu layout,
  focused-key dispatch and theme rendering remain interactive acceptance checks.
- Full `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, **10 JVM/platform
  tests passed**, strict verifier verdict **Compatible**. Log:
  `build/phase-0/menu-validation.txt`. Separate JVM source inspections remain pending.
- Archive: `build/distributions/js-console-0.1.6-dev.zip`, **84,961 bytes**.
  SHA-256: `BCA6A5C9D654472F2F904B9D23DCF708EA75BE5CBD5BE00EB0E6593B7852E569`.
- References: [Action System](https://plugins.jetbrains.com/docs/intellij/action-system.html),
  [Tool-window menu API](https://intellij-support.jetbrains.com/hc/en-us/community/posts/22571693217810-Add-an-action-as-one-of-the-ToolWindow-Options).

## Completion with changed context files: 0.1.10-dev (2026-09-26)

- The prior completion filter was general by runtime-bound name, not hardcoded to
  `lexical`, but it passed through WebStorm's auto-import suggestions for names
  absent from the running context. Editing an already imported file to add
  `initialText` exposed this gap: Bun still held the old module version, and Tab
  inserted an ES import into the REPL input.
- For bare references in the JS Console input, completion now excludes project
  source-file suggestions outside the input unless that name is bound in the
  current runtime context. Standard JavaScript library suggestions and ordinary
  editor completion remain available. When an active or added context file is
  edited, the tool-window status asks for Restart Runtime to load the edits;
  restarting is explicit because it clears console-created variables.
- Regression tests cover Tab on an added file's loaded export, a changed file's
  unbound export, preservation of standard JS suggestions, the restart hint, and
  Tab/evaluation after Restart. `test buildPlugin verifyPlugin` passed with **15
  JVM/platform tests**, Plugin Verifier reported **Compatible** for WebStorm
  2026.2.3, and all **3 Bun runtime tests** passed. Archive:
  `build/distributions/js-console-0.1.10-dev.zip`, **118,775 bytes**, SHA-256
  `3CFFAF52785B0DE369336A5565C82FF58560E2B237C0416679E84DC97908BEEA`.

## Refresh without resetting console state: 0.1.11-dev (2026-09-26)

- Bun 1.4.0 exposes imported TS modules through `require.cache`. A direct
  reimport after deleting the file's cache entry loads changed exports in the
  same process, preserving REPL declarations. On Windows, the cache key needs a
  native normalized path; the IDE sends paths with forward slashes.
- The console now records edited context files and refreshes them when its input
  receives focus or an expression is submitted. It saves the changed document,
  invalidates only that module, reimports it, updates its bound exports and
  completion names, and retains REPL variables. The same path works for files
  added to the console context. Edits arriving during a refresh are queued for
  another refresh.
- A separately edited module already loaded in Bun's cache requires a full
  Restart because direct reimport does not invalidate its dependencies. In that
  case, or when reimport fails, a native Restart action appears in the tool-window
  header; it disappears after a successful refresh or Restart. No automatic
  source-file save/reimport runs on each editor keystroke.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, **15 JVM/platform tests**,
  Plugin Verifier **Compatible** for WebStorm 2026.2.3. **4 Bun runtime tests**
  passed. Archive: `build/distributions/js-console-0.1.11-dev.zip`, **129,867
  bytes**, SHA-256 `04D7B2326F7719D9BFDE15B6DC63AED002D76A7E94C86C342C7DAE42B87A0E28`.

## Console input keys: 0.1.12-dev (2026-09-26)

- Settings → Tools → JS Console now offers two input modes: Enter inserts a
  newline and Ctrl+Enter runs (default), or Shift+Enter inserts a newline and
  Enter runs. The setting is persisted and updates open consoles immediately.
- The Enter handler applies only to the console editor and yields to an active
  completion lookup. Shift+Enter uses the IDE's normal Enter insertion handler,
  retaining indentation. In the second mode the console's Ctrl+Enter shortcut
  is unregistered and is no longer displayed on its Run Input menu item.
- `test buildPlugin verifyPlugin` passed with **15 JVM/platform tests** and
  Plugin Verifier **Compatible** for WebStorm 2026.2.3. **4 Bun runtime tests**
  passed. Archive: `build/distributions/js-console-0.1.12-dev.zip`, **136,993
  bytes**, SHA-256 `443FFD7E74196A34FEC6CA5D6DDF2CF8FDB47A7C9494914C8EBDAAD22914D4CF`.

## Multiline scrolling and edited declarations: 0.1.13-dev (2026-09-26)

- `EditorTextField` disables its vertical scrollbar by default. The console's
  multiline editor now enables it; a panel test dispatches a mouse-wheel event
  on a long input and verifies that the editor viewport moves.
- Bun's persistent REPL rejects a second top-level `const`/`let`/`class`
  declaration. When its parser reports that exact duplicate-binding error, the
  edited command runs inside a local `if` block. Earlier bindings remain in the
  session; the result appears with a note explaining the local scope. Runtime
  tests verify the user's declaration/edit pattern and prevent a runtime-thrown
  lookalike `SyntaxError` from running side effects twice. Bun 1.4 can return
  `undefined` for a scoped rerun whose final expression uses top-level `await`.
- An existing IDE test exposed a race when reimporting a TS file immediately
  after WebStorm saved it: Bun sometimes served an older module despite cache
  deletion. A short delay before reimport lets the saved source become visible
  to Bun; the focused IDE test passed twice and the full suite passed.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, **15 JVM/platform tests**,
  Plugin Verifier **Compatible** for WebStorm 2026.2.3. **6 Bun runtime tests**
  passed. Archive: `build/distributions/js-console-0.1.13-dev.zip`, **137,572
  bytes**, SHA-256 `2754B890E2CB17A2E26B7DB371303F028BAE7C63DE48868A8A9B086875EA81FD`.

## Following editor, qualified files, and resizable input: 0.1.14-dev (2026-09-26)

- Pin now keeps a file in the additional context while the main context always
  follows the selected editor. The current file has one tab labelled `follows
  editor`, with `pinned` added when it is also pinned. Equal basenames expand to
  the shortest unique `folder/file` label.
- On each editor change, the runtime binds the current file first, then restores
  pinned files. The active file owns colliding short export names. Every loaded
  file also exposes its full module namespace as `globalThis['file.ts']`, with
  matching unique path suffixes for equal basenames. Owned properties are
  removed or renamed when files change; user-owned globals are not overwritten.
- The transcript and multiline input now use the platform `JBSplitter`, with a
  draggable horizontal divider and a saved split proportion.
- Runtime tests cover colliding exports and duplicate filenames. IDE tests cover
  following plus pinning, unique tab labels, and the splitter orientation.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, **15 JVM/platform tests**,
  Plugin Verifier **Compatible** for WebStorm 2026.2.3. **7 Bun runtime tests**
  passed. Archive: `build/distributions/js-console-0.1.14-dev.zip`, **141,632
  bytes**, SHA-256 `C83CCEC987CB685ADDFB8A35A63C2856B0885F6D45ABC9D99FE2B9C385B1AF98`.

## Console redeclarations: 0.1.15-dev (2026-09-26)

- Chrome DevTools permits `const` redeclaration across separate console inputs,
  while rejecting a duplicate within one input. The previous Bun workaround ran
  the second command in a temporary block, so a subsequent lookup still saw the
  first value. That behavior was surprising and has been removed.
- The WebStorm JavaScript PSI now identifies top-level `const` and `let`
  declarations in each console input. Their runtime form is redeclarable while
  preserving the original source in the transcript and preserving line offsets.
  Nested declarations retain ordinary semantics; duplicates within one input
  are rejected before execution.
- When a console declaration uses the short name of a context-file export, only
  that automatic short binding is released. The new console value takes its
  place, while the source module's original export remains available at
  `globalThis['file.ts'].name`. A runtime test covers this ownership change and
  an IDE integration test covers two `const` inputs followed by both lookups.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, **16 JVM/platform tests**,
  Plugin Verifier **Compatible** for WebStorm 2026.2.3. **7 Bun runtime tests**
  passed. Archive: `build/distributions/js-console-0.1.15-dev.zip`, **142,960
  bytes**, SHA-256 `10BE9E19E860E86F17D07378081C9E1D97222C10DB1911C56FD921DAD5BFB766`.

## Documentation consulted

- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html): local IDE dependencies are supported; this project pins plugin 2.19.0 and Gradle 9.3.1.
- [2026 API changes](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html): confirms Java 25 for 2026.2+.
- [Bun REPL](https://bun.sh/docs/runtime/repl): native REPL features do not prove `node:repl` compatibility.
- [Chrome DevTools 92](https://developer.chrome.com/blog/new-in-devtools-92): console `const` redeclaration across separate inputs, with duplicates in one input still invalid.
- [Node REPL API](https://nodejs.org/api/repl.html): reference for the stream-backed context and `handleError`; the tested subset passes on Bun 1.4.0.

## Debugger API follow-up (2026-09-26)

- Expanded the offline audit to Bun Attach, its configuration producer, and the
  platform DAP facade. The Kotlin negative probe confirms Attach is internal,
  just like Run; ordinary plugin sources still contain no debugger dependencies.
- Found a promising public DAP route using the registered Bun adapter, but all
  required facade types are `@ApiStatus.Experimental`. In accordance with the
  handout, stopped before integrating it and documented a bounded exception,
  implementation plan and isolated acceptance checks in `debugger-blocker.md`.
- The standard producer is a conditional alternative requiring Bun as the
  project's preferred runtime and depending on the run template. It remains
  unproven; no user IDE settings were changed to force it.
- This is an API investigation, not a completed debugger milestone. Runtime
  behavior and the 0.1.15-dev version remain unchanged.

## Experimental Bun debugger integration (2026-09-27)

- User approved the bounded Experimental DAP API exception. The plugin attaches
  WebStorm's bundled Bun adapter to its own `--inspect-wait` process. A DAP
  `Attach` request is required for an existing inspector URL; `Launch` expects
  a program and terminates instead.
- The DAP session has a native Debug tab and uses an isolated test profile; it
  does not attach to the user's active IDE. Paused input goes to the selected stack-frame evaluator; commands
  run after Continue return to the Bun REPL. Restart recreates process/session.
- Isolated platform tests cover TypeScript breakpoint, local arguments, Step
  Over, Continue, console routing and restart during pause.
- The bundled adapter requires a local Node interpreter. The Gradle headless
  test profile disables only its incomplete Live Edit fixture.

## Debugger milestone validation: 0.1.16-dev (2026-09-27)

- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, 19 JVM/platform tests.
  Plugin Verifier: **Compatible** with WebStorm 2026.2.3; 20 usages of the
  approved Experimental DAP facade, with all other failure levels enforced.
- 7 Bun runtime tests passed. `git diff --check` passed.
- Archive: `build/distributions/js-console-0.1.16-dev.zip` (166,703 bytes),
  SHA-256 `DF1838C738A7DC652D223970892645A05DE2AD6F9CC45917DE4FB939D25F8138`.
- No installation or manual run in the user's working IDE was performed.

## UI and late-breakpoint validation: 0.1.17-dev (2026-09-27)

- A compact Run button beside the input displays the current shortcut. Native
  context tabs list pinned files first and an unpinned file following the editor
  last; an active pinned file has just one selected tab. Clicking a pinned tab
  opens that file in the editor. The Bun Inspector banner uses neutral console
  output rather than error red.
- A breakpoint added after its TypeScript file has loaded now gates the next
  evaluation until WebStorm's breakpoint presentation is updated (with a bounded
  fallback), then reloads the module through the existing context refresh path.
  This can re-run top-level module side effects, but preserves REPL bindings.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, 20 JVM/platform tests.
  Plugin Verifier: **Compatible** with WebStorm 2026.2.3; 20 usages of the
  approved Experimental DAP facade. 7 Bun runtime tests passed.
- Archive: `build/distributions/js-console-0.1.17-dev.zip`. No installation or
  manual run in the user's working IDE was performed.

## Background debugger and reload synchronization: 0.1.18-dev (2026-09-27)

- The DAP session still starts immediately because suspend-only initialization
  deadlocks Bun `--inspect-wait`, but Debug is hidden after the runtime handshake
  and JS Console regains focus. Debug activates when a breakpoint pauses. Input
  in JS Console remains available for selected-frame evaluation while paused.
- Editing a current or pinned file with an enabled JavaScript breakpoint restarts
  only the console's Bun runtime before the next command. This avoids Bun's stale
  breakpoint binding after TypeScript recompilation and preserves transcript,
  history, input and file-context selection. Runtime variables reset.
- `BunDebuggerModuleInitializationTest` covers the reported multiline module,
  exact `b.ts:9` pause, string-local evaluation, file edit, automatic restart and
  a second pause with the updated value.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, 21 JVM/platform tests.
  Plugin Verifier: **Compatible** with WebStorm 2026.2.3; 20 usages of the
  approved Experimental DAP facade. 7 Bun runtime tests passed.
- Archive: `build/distributions/js-console-0.1.18-dev.zip` (175,475 bytes),
  SHA-256 `97588A06D492A433BB6F137416A189CAED642B24D2E19AF7E7E58226A7EB3FCA`.
- `git diff --check` passed. No installation, attach, restart or manual run in
  the user's working WebStorm was performed.

## Independent console lifetime: 0.1.19-dev (2026-09-28)

- Stopping WebStorm's Debug session no longer closes the JS Console Bun process.
  The console continues evaluating commands without breakpoints and tells the
  user that Restart Runtime will reconnect the debugger.
- `BunDebuggerServiceTest` now stops the Debug session and verifies that the same
  console runtime still evaluates the next command.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, 21 JVM/platform tests.
  Plugin Verifier: **Compatible** with WebStorm 2026.2.3; the approved 20
  Experimental DAP usages are unchanged. 7 Bun runtime tests passed.
- Archive: `build/distributions/js-console-0.1.19-dev.zip` (175,523 bytes),
  SHA-256 `9337BEAA7D760F55298AE0CA027E0F1E3EFF654B36D3378FA838B24A0DD1836B`.
- No installation, attach, restart or manual run in the user's working WebStorm
  was performed.

## Optional debugger mode: 0.1.20-dev (2026-09-28)

- Settings → Tools → JS Console now has **Start console with debugger**, disabled
  by default. Console-only mode launches the Bun bootstrap directly, without
  Inspector flags, `BunDebugBridge`, DAP or an XDebugger session.
- Enabling the option applies on the next Restart Runtime and preserves the
  existing debugger behavior. Runtime settings never interrupt the current
  console when Apply is pressed.
- In debugger mode, the Debug hide callback now explicitly shows and activates
  JS Console. This serializes the two tool-window operations and prevents Debug
  from hiding after an earlier console activation request.
- Settings, console-only service and debugger integration tests cover both paths.
- `test buildPlugin verifyPlugin`: **BUILD SUCCESSFUL**, 21 JVM/platform tests.
  Plugin Verifier: **Compatible** with WebStorm 2026.2.3; the approved 20
  Experimental DAP usages are unchanged. 7 Bun runtime tests passed.
- Archive: `build/distributions/js-console-0.1.20-dev.zip` (176,398 bytes),
  SHA-256 `DE382EF46874012BFD5F6CFDE6F5B900D52E295B793BF2FF7390BE7D9251507A`.
- No installation, attach, restart or manual run in the user's working WebStorm
  was performed.

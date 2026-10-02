# Developing Bun Console

## Prerequisites

- **Java 25.** WebStorm's bundled JBR works: point `JAVA_HOME` at `<WebStorm>/jbr`.
- **Bun 1.4+** on `PATH` (runtime tests and the plugin itself).
- **Node.js** installed at `C:/Program Files/nodejs/node.exe` for the debugger tests (the Bun
  debug adapter runs on Node.js; the tests configure that interpreter path).
- The Gradle wrapper pins Gradle 9.3.1 and the IntelliJ Platform Gradle Plugin 2.x.

## Build and test

On Windows, `test.bat` runs everything without any setup: it uses the Java bundled with WebStorm
(`%WEBSTORM_PATH%`, default `%LOCALAPPDATA%\Programs\WebStorm`), runs the Bun runtime tests, then
the IDE tests. Extra arguments go to Gradle:

```bat
test.bat
test.bat --tests dev.bunconsole.ConsoleImportsTest
```

The same by hand:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\WebStorm\jbr"
.\gradlew.bat -PwebstormPath="$env:LOCALAPPDATA\Programs\WebStorm" test buildPlugin verifyPlugin
bun test .\tests\runtime\bootstrap.test.mjs
```

Without `-PwebstormPath`, Gradle downloads WebStorm 2026.2.3. The plugin archive is written to
`build/distributions/bun-console-<version>.zip`.

- `test` runs the IDE tests in `src/test/kotlin` in a headless WebStorm (one JVM per test class;
  about four minutes). They start real Bun processes and, for the debugger tests, WebStorm's Bun
  debug adapter.
- `bun test tests/runtime/bootstrap.test.mjs` tests the runtime script directly over its socket
  protocol (seconds).
- `verifyPlugin` runs Plugin Verifier against the target IDE. It must report *Compatible*; only
  the approved Experimental DAP usages may be listed (see [debugger-blocker.md](debugger-blocker.md)).

## Sandbox IDE

```powershell
.\gradlew.bat -PwebstormPath="$env:LOCALAPPDATA\Programs\WebStorm" runIde
```

`runIde` starts WebStorm with the plugin in a separate profile under `build/isolated-ide`; your
own WebStorm settings and windows are not touched. Open `tests/runtime/fixtures` as the project:
it contains manual test files (see its [README](../tests/runtime/fixtures/README.md)) that follow
the [acceptance checks](acceptance.md).

In the sandbox IDE the plugin writes a development trace to `build/bun-console-trace.log`: every
input, output fragment, status and tab change, editor switch and message exchanged with Bun. It is
enabled only by the `bun.console.trace` system property that `runIde` sets.

## Layout

| Path | Contents |
| --- | --- |
| `src/main/resources/runtime/bootstrap.mjs` | The Bun side: REPL, context bindings, lazy modules, socket protocol |
| `src/main/kotlin/dev/bunconsole/runtime` | Bun process, input preparation (`ConsoleImports`), declarations, trace |
| `src/main/kotlin/dev/bunconsole/service` | `BunConsoleProjectService`: lifecycle, contexts, reloads, status |
| `src/main/kotlin/dev/bunconsole/debug` | Debugger attachment and paused-frame evaluation |
| `src/main/kotlin/dev/bunconsole/ui` | Tool window, input, tabs, actions, completion |
| `tests/runtime` | Runtime tests and the manual-test fixtures |
| `docs/` | Acceptance checks, debugger notes, publishing guide, investigation notes |

## Runtime protocol

The IDE listens on a loopback port and starts Bun with the port and a one-time token in its
environment; the bootstrap connects back and sends `{"event":"ready","token":…}`. Requests are
JSON lines `{id, op, …}` answered by `{id, ok, …}`; console output travels as
`{event: "output", text, level}` on the same socket, so it stays in order with results.

| Operation | Purpose |
| --- | --- |
| `eval` | Run console input (`code`, `imports`, `declarations`); replies with `text` and the user's `globals` |
| `load` | Make a file the editor context (`path`, `declared`, `dependencies`) without running it |
| `add_file` / `remove_file` | Add or remove a pinned/added context file |
| `add_symbol` | Bind one top-level declaration of a file |
| `reload_file` | Re-read a changed context file; it runs again on next use |
| `cached_files` | Loaded module paths, to detect changed dependencies |
| `ping` | Answered outside the command queue; a late answer means the JavaScript thread is busy |

## Further notes

- [Acceptance checks](acceptance.md) — automated and manual checks before a release.
- [Debugger integration](debugger-blocker.md) — why and how the Experimental DAP facade is used.
- [Publishing](marketplace.md) — Marketplace and GitHub release guide.
- [Phase 0 findings](phase-0-findings.md) and the original [handout](../CODEX_HANDOUT_BUN_CONSOLE.md)
  — the initial investigation and requirements.
- [WebStorm startup issue](webstorm-startup-issue.md) — an IDE error unrelated to the plugin.

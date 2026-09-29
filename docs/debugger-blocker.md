# Bun debugger integration

Target: WebStorm 2026.2.3 (WS-262.10968.77), Bun 1.4.0.

When **Start console with debugger** is enabled, the console starts Bun with
`--inspect-wait` and attaches WebStorm's bundled Bun debug adapter before the
bootstrap executes. Breakpoints in TypeScript
modules stop normal console calls. While stopped, input is evaluated through the
selected `XStackFrame` evaluator; Continue returns subsequent input to the Bun
REPL. Restart closes this console's process/session and creates both again.
Stopping the Debug session detaches only the debugger; the existing Bun REPL
continues without breakpoints until Restart reconnects a new debug session.
The session has a native Debug tab for breakpoint and frame state, but initialization
returns focus to Bun Console and keeps Debug out of the way until a breakpoint
pauses execution. Continue and stepping are also available in the console menu
while paused, the status line above the input shows the pause location, and completion offers the names in scope. You can switch
back to Bun Console while paused and evaluate expressions in the selected frame.

This uses the public **Experimental** `com.intellij.platform.dap` facade. The
user explicitly approved that bounded exception on 2026-09-27. The integration
uses `DebugAdapterSupportProvider`, `DapProcessStarter`, `DapLaunchArgumentsProvider`
and `XDebugSession`, with no reflection, Internal Bun configuration classes,
custom protocol implementation or changes to the user's IDE. WebStorm's Bun
adapter uses a DAP `attach` request with the inspector URL. It runs under a local
Node.js interpreter configured in WebStorm.

## Why the exception was needed

Both `BunRunConfiguration` and `BunAttachConfiguration` are Kotlin `internal`.
`BunSettingsService` and `JSRuntimeProvider` carry `@ApiStatus.Internal`. The
negative Kotlin probe at `spikes/jetbrains-api/BunApiProbe.kt` verifies that the
configuration classes are unavailable to third-party Kotlin code. The generic
DAP facade is public but Experimental in the installed 262 build; no stable Bun
launch/attach API was found. The exception is limited to the debugger package,
and the plugin remains pinned to the 262 build range.

## Verified behavior

`BunDebuggerTest` covers a regular WebStorm breakpoint in a TypeScript function,
local evaluation, Step Over, Continue and completion of the original REPL request.
`BunDebuggerServiceTest` covers the same console input routing, pause label,
restart during a pause, evaluation in the new runtime, and continued console
evaluation after the Debug session is stopped. `BunLateBreakpointTest`
adds a breakpoint after the console has loaded a TypeScript module and immediately
executes its function. The console waits for WebStorm's breakpoint presentation,
then reloads that module through its normal context-refresh path before running
the command. This handles a Bun adapter limitation with breakpoints added to an
already loaded source. Reloading re-executes top-level code in that file; the REPL
bindings remain intact. All tests use an isolated headless WebStorm profile. No
debugger was attached to the user's working IDE.

BunDebuggerModuleInitializationTest covers string locals and a TypeScript module
whose breakpoint exists before startup. After that file is edited, the console
automatically restarts its own runtime before the next command, stops at the
correct source line and exposes the updated local value. This restart is needed
because Bun invalidates the adapter's breakpoint binding when it recompiles the
module. It preserves the transcript, history, input and context-file selection,
while ordinary runtime variables reset.

Plugin Verifier reports 20 Experimental API usages and Compatible for this
WebStorm build. The Gradle verification task treats only that approved category
as non-fatal; all other verification failure levels remain enabled.

`showToolWindowOnSuspendOnly(true)` cannot be used with Bun `--inspect-wait` in
this build: it defers adapter initialization until pause, while Bun waits for
the adapter to connect before executing. We initialize the session with
`showTab(true)` and `showToolWindowOnSuspendOnly(false)` so the adapter connects
and WebStorm owns the Debug tab's lifetime. When the runtime handshake completes,
the plugin hides Debug if it was not already visible and restores Bun Console if
startup stole focus; `sessionPaused` activates Debug when a breakpoint is hit.
Recheck this workaround when upgrading WebStorm.

Debugger mode is optional and disabled by default. Console-only mode starts the
same Bun bootstrap without `--inspect-wait`, does not instantiate `BunDebugBridge`
and does not create a DAP/XDebugger session. The **Debugger** toggle in the console
title bar and menu changes the setting and restarts the runtime at once; changing
it in Settings affects the next Restart Runtime. Attaching to an already running
runtime without a restart is not offered: Bun must start with `--inspect-wait`,
and the adapter does not bind breakpoints to sources Bun has already loaded. In debugger mode the hide callback explicitly shows and activates
Bun Console, so the temporary Debug activation cannot leave both windows hidden.

The installed Live Edit plugin has an incomplete headless test fixture; the
Gradle test task disables Live Edit only in `build/isolated-ide/.../config-test`.
It does not alter any normal WebStorm profile.

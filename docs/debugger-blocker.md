# Always-on debugger: current blocker

Target inspected: WebStorm 2026.2.3 / WS-262.10968.77.

No supported Bun launch route has been established. The console-only milestone
does not attach a debugger; WebStorm breakpoints will not stop its Bun process.

## Evidence from the installed artifacts

- `com.intellij.javascript.bun.runConfiguration.run.BunRunConfiguration` and
  `BunRunConfigurationType` look public in JVM signatures, but are **Kotlin internal**.
  Compiling `spikes/jetbrains-api/BunApiProbe.kt` with Kotlin 2.4.0 rejects access
  with `Cannot access ... it is internal in file`. This is a negative feasibility
  check, not a failure in normal plugin sources. No visibility suppression is used.
- `BunRunConfigurationOptions` also has internal visibility in its Kotlin
  metadata; it is not used by the plugin.
- `com.intellij.javascript.bun.settings.BunSettingsService` is explicitly annotated
  `@ApiStatus.Internal`. Its companion's `getConfiguredPath(Project)` is therefore
  not a permissible workaround. The console uses executable selection/PATH instead.
- Follow-up on 2026-09-24: the generic
  `com.intellij.javascript.runtime.JSRuntimeProvider` interface in
  `intellij.javascript.backend.jar` is also explicitly `@ApiStatus.Internal`.
  It is not a supported facade for bypassing the Bun-specific service restriction.
  The inspected signature is in `build/phase-0/api/JSRuntimeProvider.txt`.
- `com.intellij.javascript.bun.BunDebugAdapterSupportProvider` is package-private
  at JVM level. Its implementation is not a callable public API for our plugin.
- The installed `intellij.javascript.debugger.dap.launcher` module contains one
  implementation class, `LauncherKt`. Its public function is
  `NodeJsBasedDebugAdapter(Project, String, List<String>, Map<String,String>, Integer)`.
  It returns a `DebugAdapterHandle` for a Node-hosted adapter. It is not a complete
  Bun run/session factory. Calling it alone does not establish a supported path
  for Bun launch configuration, process/session association, and breakpoint wiring.

Using the generic run-configuration registry does not by itself solve supplying
the Bun program/runtime parameters through supported typed APIs. Undocumented
serialization fields have not been adopted as a replacement for the internal
configuration API. This finding is scoped to the paths investigated; it is not a
claim that no public integration could ever exist.

## Upstream API request

Expose a stable Bun run/attach configuration interface or factory that accepts:

- the Bun executable (or a public runtime selection service);
- bootstrap path, working directory, arguments, and environment;
- Debug executor launch without forcing the Debug tool window to the foreground;
- a supported way to identify the resulting process and `XDebugSession`.

Then the console can delegate breakpoints, stepping, and source maps to JetBrains,
and route paused evaluations through public `XStackFrame.getEvaluator()`.

## Reproduce the visibility check

First run `scripts/inspect-bun-api.ps1` to place a read-only copy of the Bun JAR
under `build/phase-0/api/`. Then:

```powershell
.\gradlew.bat -PwebstormPath="C:\path\to\WebStorm" -PapiAudit=true compileKotlin
```

This command is expected to fail. Normal builds omit `-PapiAudit=true` and never
include the probe or the copied Bun JAR. Detailed local output is saved in
`build/phase-0/api/kotlin-accessibility.txt`.

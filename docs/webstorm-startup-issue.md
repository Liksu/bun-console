# WebStorm 262 startup LoadingState error

Reproduced on 2026-09-24 with WebStorm **2026.2.3 / WS-262.10968.77**,
bundled JBR **25.0.4**, Windows 11, **without Bun Console installed**.

This is separate from the Bun Console `saveContext` write-safe scheduling error
fixed in 0.1.3. It is an IDE startup error printed while `runIde` is running,
not a Kotlin compilation error.

## Evidence

A separate Java process launched the installed IDE using its product-info.json
classpath and JVM options. Config, system, log and empty custom-plugin directories
were all under `build/startup-baseline/`. No `plugin.path` or required Bun Console
plugin was supplied. Working/test IDE profiles already open by the user were not
modified or terminated. Only diagnostic child processes were stopped.

Fresh/welcome startup and explicitly opening a disposable project did not trigger
the reported error in the observed runs. Restoring that project on startup via
the separate profile's `recentProjects.xml` reproduced it at **04:00:50.563**, 1336
milliseconds after startup. Gradle-style sandbox flags and `idea.is.internal=true`
were enabled. The custom-plugin directory was empty.

The stack matches the user's report:

```text
Should be called at least in the state COMPONENTS_LOADED,
the current state is: CONFIGURATION_STORE_INITIALIZED
LoadingState.logStateError(LoadingState.java:64)
LoadingState.checkOccurred(LoadingState.java:60)
Registry$Companion.getInstance(Registry.kt:206)
Registry$Companion.is(Registry.kt:100)
PlatformTaskSupportKt.isRhizomeProgressModelEnabled(PlatformTaskSupport.kt:95)
TaskInfoEntityCollectorKt$showTaskIndicator$1$1.invokeSuspend(TaskInfoEntityCollector.kt:67)
```

Local diagnostic files (ignored build output):

- `build/startup-baseline/log/idea.log` (matching error at line 1590)
- `build/startup-baseline/restore.args` (JVM arguments)
- `build/startup-baseline/config/options/recentProjects.xml`
- `build/startup-baseline/plugins/` (empty)

Missing JPS-module warnings and service-override warnings also appeared without
Bun Console. They are not evidence of a plugin build failure.

## Status

The platform startup defect remains unfixed in this plugin. We do not patch
JetBrains classes, suppress the logger, or change the user's IDE settings.
Avoiding automatic project restoration in the **test IDE** and opening the
project after startup is a possible workaround based on the observed runs, not a
guarantee against a timing-dependent platform error. The reproduction is suitable
for a JetBrains issue report; none has been submitted automatically.

The public JetBrains source also places the registry lookup in the platform
progress implementation: [PlatformTaskSupport.kt](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/openapi/progress/impl/PlatformTaskSupport.kt).
The locally reproduced stack is the evidence for this particular installed build.

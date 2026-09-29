# Publishing to JetBrains Marketplace

## Compatibility decision (Experimental DAP API)

The console itself uses only stable platform and JavaScript plugin APIs. The
optional debugger attaches WebStorm's Bun debug adapter through the public but
**Experimental** `com.intellij.platform.dap` facade; no stable alternative exists
(see [debugger-blocker.md](debugger-blocker.md)).

Decision (0.2.0): publish **without an upper IDE bound** (`until-build` unset) and
let the debugger degrade instead of pinning the whole plugin to one build:

- Any failure to attach the debugger, including `LinkageError` from a changed
  DAP API, is reported in the transcript; the console restarts without the
  debugger and keeps working. Toggling **Debugger** off and on retries.
- Marketplace runs Plugin Verifier against new IDE builds and reports
  incompatibilities to the vendor. Check each new major (EAP) build with
  `verifyPlugin` and ship an update if the DAP facade changed.
- If the `intellij.platform.dap` module were removed entirely, the plugin would
  not load in that build; Plugin Verifier flags this during EAP.

Pinning `until-build` to `262.*` is the conservative alternative: no risk of a
broken debugger, but a mandatory release for every IDE version.

## Release checklist

1. Bump `version` in `build.gradle.kts` and add `<change-notes>` in `plugin.xml`.
2. Run the checks (Java 25 = WebStorm's bundled JBR):
   ```powershell
   $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\WebStorm\jbr"
   .\gradlew.bat -PwebstormPath="$env:LOCALAPPDATA\Programs\WebStorm" test buildPlugin verifyPlugin
   bun test .\tests\runtime\bootstrap.test.mjs
   ```
   Plugin Verifier must report *Compatible*; only the approved Experimental DAP
   usages may be listed.
3. Walk through [acceptance.md](acceptance.md) in `runIde`.
4. Upload `build/distributions/bun-console-<version>.zip` at
   <https://plugins.jetbrains.com/plugin/add> (first release) or the plugin's
   *Versions* page. Automated publishing needs a Marketplace token
   (`intellijPlatform.publishing.token`) and is not configured.

## Before the first upload

- **Plugin ID** `dev.bunconsole` can never change after the first upload. It
  should be a reverse domain you control.
- **Vendor**: `plugin.xml` names the vendor; Marketplace also shows the uploading
  account's vendor profile (optionally with a URL and e-mail).
- **License / EULA** is chosen in the Marketplace upload form; add a matching
  `LICENSE` file if the source repository becomes public.
- `CODEX_HANDOUT_BUN_CONSOLE.md` and `docs/` describe internal development; decide
  whether they belong in a public repository.
- Name *Bun Console* and ID `dev.bunconsole` were unused on Marketplace on 2026-09-28.

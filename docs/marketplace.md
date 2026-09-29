# Publishing Bun Console

This is the step-by-step guide for releasing the plugin: a GitHub release with the zip, the
JetBrains Marketplace listing, and the GitHub Pages site.

## 1. Before the first release (once)

- **Plugin ID.** `<id>` in `plugin.xml` can never change after the first Marketplace upload.
  It should be a reverse domain you control, for example `io.github.liksu.bunconsole`.
- **Vendor.** `<vendor>` in `plugin.xml` is shown on the plugin page. Marketplace also shows the
  vendor profile of the uploading account (name, optional website and e-mail).
- **License.** Add a `LICENSE` file to the repository. The same license is selected in the
  Marketplace upload form, and an open-source plugin must link to its source code.
- **Screenshots.** Put them into `docs/images/` (see [Screenshots](#screenshots)); the README uses
  `docs/images/console.png`.
- **JetBrains account.** Sign in at <https://plugins.jetbrains.com> with the JetBrains Account
  that will own the plugin.

## 2. Prepare a release

1. Set `version` in `build.gradle.kts` (for example `0.2.0`) and describe the changes in
   `<change-notes>` in `src/main/resources/META-INF/plugin.xml` (no placeholder text).
2. Run all checks (Java 25 = WebStorm's bundled JBR):
   ```powershell
   $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\WebStorm\jbr"
   .\gradlew.bat -PwebstormPath="$env:LOCALAPPDATA\Programs\WebStorm" test buildPlugin verifyPlugin
   bun test .\tests\runtime\bootstrap.test.mjs
   ```
   Plugin Verifier must say *Compatible*; the only listed issues may be the approved
   Experimental DAP usages ([why](#compatibility-experimental-dap-api)).
3. Walk through the manual [acceptance checks](acceptance.md) in `runIde`.
4. The archive to publish is `build/distributions/bun-console-<version>.zip`.
5. Commit, tag and push:
   ```bash
   git tag v0.2.0
   git push origin main --tags
   ```
6. On GitHub: *Releases → Draft a new release*, choose the tag, paste the change notes and attach
   the zip. This is the "install from disk" download linked in the README.

## 3. First upload to JetBrains Marketplace

1. Open <https://plugins.jetbrains.com/plugin/add>.
2. Accept the **JetBrains Marketplace Developer Agreement** (first upload only).
3. **Vendor profile.** Create one (personal or organization) or select an existing one.
4. Fill in the form:
   - **Plugin file:** the zip from step 2.4 (up to 400 MB).
   - **License:** the license of the repository, with the source link
     `https://github.com/Liksu/bun-console`.
   - **Tags:** for example *JavaScript*, *TypeScript*, *Debugging*, *Code tools*.
   - **Channel:** leave *Stable* (use a custom channel such as `eap` for pre-releases).
5. Submit. Name, description, change notes and icon come from `plugin.xml` and
   `META-INF/pluginIcon.svg`; they cannot be edited in the form.
6. JetBrains reviews every new plugin manually; expect a decision within **3–4 working days**.
   The e-mail either approves the plugin or lists what to fix; re-upload a fixed zip.

What the review checks (JetBrains Marketplace approval guidelines):

- original name without "Plugin"/"IntelliJ", at most 30 characters — *Bun Console* ✓;
- a custom 40×40 SVG logo — `pluginIcon.svg` ✓;
- English description and meaningful change notes ✓;
- no internal API usage; Plugin Verifier run on every upload ✓ (experimental API is not
  forbidden, see below);
- an end-user license, and a source link for open-source plugins;
- a privacy policy only if the plugin collects personal data (Bun Console collects none).

## 4. After approval: the plugin page

On the plugin page (*Edit* mode):

- **Screenshots/media:** upload the screenshots from `docs/images/`.
- **Links:** source code `https://github.com/Liksu/bun-console`, issue tracker
  `https://github.com/Liksu/bun-console/issues`, documentation — the GitHub Pages site.
- **Getting started:** a short text can be copied from the README's *Quick start*.

## 5. Next versions

Repeat section 2, then on the plugin page open *Versions → Upload Update* and upload the new zip.
Updates are checked automatically; they usually appear within hours.

Optional automation: create a *Permanent Token* in your Marketplace profile, add
`publishing { token = providers.environmentVariable("PUBLISH_TOKEN") }` inside the
`intellijPlatform { … }` block of `build.gradle.kts`, and run `.\gradlew.bat publishPlugin` with
`PUBLISH_TOKEN` set in the environment. Never commit the token. The first upload must still be
done by hand.

## Screenshots

- At least **1200×760** pixels; all screenshots with the **same aspect ratio** (for example
  1600×1000).
- Crop to the IDE window, no desktop or browser around it; readable text (editor font 15–16,
  UI zoom 100–125 %).
- Suggested set:
  1. `console.png` — a TypeScript file in the editor and Bun Console below, with a few
     commands: a call of an exported and of a non-exported function, an object result, a
     `console.log` line, and the completion popup showing a context name.
  2. `debugger.png` — paused at a breakpoint in a context file: status line
     `paused at …`, a local variable evaluated in the console, the Debug window visible.
  3. `async.png` (optional) — `await sleep(5000)` running while `1 + 1` has already answered,
     `running…` in the status line.

## GitHub Pages

The repository root is published as a site with Jekyll (`_config.yml`), using the README as the
home page. Enable it once: *Settings → Pages → Build and deployment → Deploy from a branch →
`main` / `(root)` → Save*. The site appears at <https://liksu.github.io/bun-console/> after a
minute and is rebuilt on every push to `main`.

## Compatibility (Experimental DAP API)

The console itself uses only stable platform and JavaScript plugin APIs. The optional debugger
attaches WebStorm's Bun debug adapter through the public but **Experimental**
`com.intellij.platform.dap` facade; no stable alternative exists
(see [debugger-blocker.md](debugger-blocker.md)). The Marketplace guidelines forbid internal API,
not experimental API.

Decision (0.2.0): publish **without an upper IDE bound** (`until-build` unset) and let the debugger
degrade instead of pinning the whole plugin to one IDE build:

- Any failure to attach the debugger, including a `LinkageError` from a changed DAP API, is
  reported in the transcript; the console restarts without the debugger and keeps working.
- Marketplace runs Plugin Verifier against new IDE builds and e-mails the vendor about
  incompatibilities. Check each new major (EAP) build with `verifyPlugin` and ship an update if
  the DAP facade changed.
- If the `intellij.platform.dap` module were removed entirely, the plugin would not load in that
  build; Plugin Verifier reports this during EAP.

Pinning `until-build` to `262.*` is the conservative alternative: no risk of a broken debugger,
but a mandatory release for every IDE version.

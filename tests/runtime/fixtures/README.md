# Manual test files (the `runIde` sandbox opens this folder)

Scratch files for checking Bun Console by hand in `runIde` (see
[docs/acceptance.md](../../../docs/acceptance.md), checks 17–24). Each file lists what
to type in its comments.

| File | What it checks |
| --- | --- |
| `lazy.ts` | tab switch runs nothing; non-exported functions/classes/variables by name; live values |
| `output.ts` | `console.*` output order before the result; one shared realm (`instanceof`) |
| `async.ts` | awaits don't block, `running…`, uncaught errors, busy thread, child processes on Restart |
| `debug-target.ts` | console debugger: breakpoint, locals in the same input, Continue |
| `app-server.mjs` | evaluating in *your* paused program (WebStorm Debug), not the console runtime |
| `store.ts` + `uses-store.ts` | a file first loaded as another module's dependency still exposes its non-exported names |
| `legacy.cjs` | CommonJS: only `module.exports` |
| `repl.md` | plain console behavior without a context file |

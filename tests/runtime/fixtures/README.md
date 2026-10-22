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
| `legacy.cjs` | CommonJS: `module.exports` and top-level names (`calls`) |
| `mortgage.ts` | Run Selection with the file's declarations; a two-column screenshot (see below) |
| `repl.md` | plain console behavior without a context file |

## Screenshot: editor and console side by side

1. Console ⋮ → *Move to → Right Top*; *Settings → Appearance → Widescreen tool window layout*.
2. Open `mortgage.ts` (fresh runtime: Restart Runtime first).
3. Select the last line (`[3, 4, 5].map(…)`) and press **Run Selection**: the rates table
   appears without running the file.
4. Type into the console:
   ```js
   round(monthlyPayment())
   totalInterest()
   console.table(schedule().filter((row) => row.month % 60 === 0))
   ```
5. Change `yearlyRate` to `0.035`, save, and run `totalInterest()` again: the console picks up
   the edit (200249.23 → 150561.21).

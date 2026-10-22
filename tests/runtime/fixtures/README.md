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
| `mortgage.ts` | Run File output, Run Selection; a two-column screenshot (see below) |
| `repl.md` | plain console behavior without a context file |

## Screenshot: editor and console side by side

1. Console ⋮ → *Move to → Right Top*; *Settings → Appearance → Widescreen tool window layout*.
2. Open `mortgage.ts` (fits 40 lines × 78 columns) and press **Run File**: the monthly payment,
   the total interest and a table of every 5 years appear in the console.
3. Select the last line (`[3, 4, 5].map(…)`) and press **Run Selection**: payments at 3, 4 and 5 %.
4. Change `yearlyRate` to `0.035` and press **Run File** again: the console shows the new numbers.

# Console-only checks (no file needed)

Open any non-JS file (or this one) so the context is plain JavaScript.

| Type | Expect |
| --- | --- |
| `const x = 1` then `const x = 2` then `x` | `2`, no "already declared" error |
| `class K { v = 1 }` then `class K { v = 2 }` then `new K().v` | `2` |
| `{a: 1}` | `{ a: 1 }` (object, as in Chrome) |
| `import path from 'path'` + newline + `path.basename('/a/b.txt')` | `'b.txt'` |
| `await fetch('https://example.com').then(r => r.status)` | `200` |
| `fetch('http://127.0.0.1:1')` | a Promise, then `Uncaught (in promise) …`; console keeps working |
| `new Date(0)` | `1970-01-01T00:00:00.000Z` |

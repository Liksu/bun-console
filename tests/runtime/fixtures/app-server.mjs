// Check 23: YOUR program paused in WebStorm's own debugger (not the console's runtime).
//
// 1. Right-click this file → Debug 'app-server.mjs' (Node.js or Bun, either works).
// 2. Put a breakpoint on the `const reply` line; it is hit every 2 seconds.
// 3. With the program paused, open JS Console (its Debugger toggle may stay OFF).
//    The context tab shows "paused in app-server.mjs at app-server.mjs:15".
// 4. Type: request      request.path      counter      reply   (undefined until the line runs)
// 5. ⋮ → Continue resumes the program; the next console input runs in the console's own Bun again.
// Stop the Debug session when done.

let counter = 0;

function handle(request) {
  counter++;
  const reply = { status: 200, body: `hello ${request.user}`, served: counter };
  return reply;
}

setInterval(() => {
  const result = handle({ path: "/greet", user: `user-${counter}` });
  console.log(result);
}, 2000);

import { test, expect } from "bun:test";
import { spawn } from "node:child_process";
import net from "node:net";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const fixture = (name) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url));

async function start() {
  const token = randomUUID();
  const listener = net.createServer();
  await new Promise((resolve) => listener.listen(0, "127.0.0.1", resolve));
  let socket;
  let counter = 0;
  let text = "";
  let stdout = "";
  const jobs = new Map();
  const ready = new Promise((resolve, reject) => {
    listener.on("connection", (connection) => {
      socket = connection;
      socket.setEncoding("utf8");
      socket.on("data", (chunk) => {
        text += chunk;
        let end;
        while ((end = text.indexOf("\n")) >= 0) {
          const message = JSON.parse(text.slice(0, end));
          text = text.slice(end + 1);
          if (message.event === "ready") {
            if (message.token === token) resolve(); else reject(new Error("Bad token"));
          } else {
            const job = jobs.get(message.id);
            jobs.delete(message.id);
            if (message.ok) job?.resolve(message); else job?.reject(new Error(message.error));
          }
        }
      });
    });
  });
  const process = spawn(Bun.which("bun") ?? Bun.argv[0], [
    fileURLToPath(new URL("../../src/main/resources/runtime/bootstrap.mjs", import.meta.url)),
  ], {
    env: { ...globalThis.process.env, JS_CONSOLE_PORT: String(listener.address().port), JS_CONSOLE_TOKEN: token },
    stdio: ["ignore", "pipe", "pipe"], windowsHide: true,
  });
  process.stdout.on("data", (chunk) => { stdout += chunk; });
  process.stderr.on("data", (chunk) => { stdout += chunk; });
  const closed = new Promise((resolve) => process.once("close", resolve));
  const request = (op, fields = {}) => new Promise((resolve, reject) => {
    const id = ++counter;
    const timeout = setTimeout(() => { jobs.delete(id); reject(new Error(`Timeout: ${op}; ${stdout}`)); }, 3000);
    jobs.set(id, {
      resolve: (value) => { clearTimeout(timeout); resolve(value); },
      reject: (error) => { clearTimeout(timeout); reject(error); },
    });
    socket.write(`${JSON.stringify({ id, op, ...fields })}\n`);
  });
  const stop = async () => {
    socket?.destroy();
    listener.close();
    process.kill();
    await closed;
  };
  let startupTimeout;
  try {
    await Promise.race([ready, new Promise((_, reject) => {
      startupTimeout = setTimeout(() => reject(new Error(`Startup timeout: ${stdout}`)), 4000);
    })]);
  } catch (error) { await stop(); throw error; }
  finally { clearTimeout(startupTimeout); }
  return { request, stop, stdout: () => stdout };
}

test("persistent context, TS exports, collisions, ownership, errors, and independent output", async () => {
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    await run("const lexical = 17; const undefinedUser = undefined");
    const loaded = await runtime.request("load", { path: fixture("a.ts") });
    expect(loaded.names).toEqual(["a", "count", "increment", "twice"]);
    expect(loaded.collisions).toEqual(["lexical", "process", "undefinedUser"]);
    expect(loaded.unsupported).toEqual(["default"]);
    expect((await run("twice(21)")).text).toBe("42");
    expect((await run("increment(); count")).text).toBe("1");
    expect((await run("await Promise.resolve(42)")).text).toBe("42");
    const http = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: () => new Response("42") });
    try { expect((await run(`await fetch("http://127.0.0.1:${http.port}").then(r => r.text())`)).text).toBe("'42'"); }
    finally { http.stop(true); }
    expect((await run("lexical")).text).toBe("17");
    await runtime.request("eval", { code: "var a = 42", declarations: ["a"] });
    expect((await run("a")).text).toBe("42");
    expect((await run("globalThis['a.ts'].a")).text).toBe("'A'");
    await runtime.request("load", { path: fixture("b.ts") });
    expect((await run("typeof twice")).text).toBe("'undefined'");
    expect((await run("a")).text).toBe("42");
    const bValue = (await run("b")).text;
    expect(bValue).toBe((await run("globalThis['b.ts'].b")).text);
    await expect(run("throw new Error('intentional')")).rejects.toThrow("intentional");
    await expect(run("await Promise.reject(new Error('async failure'))")).rejects.toThrow("async failure");
    await expect(run("let = ;")).rejects.toThrow();
    expect((await run("6 * 7")).text).toBe("42");
    await expect(runtime.request("load", { path: fixture("missing.ts") })).rejects.toThrow();
    expect((await run("b")).text).toBe(bValue);
    await run('console.log(\'{"id":999,"ok":false}\'); 42');
    expect((await run("21 * 2")).text).toBe("42");
    for (let i = 0; i < 50 && !runtime.stdout().includes('"id":999'); i++) await Bun.sleep(10);
    expect(runtime.stdout()).toContain('"id":999');
  } finally { await runtime.stop(); }
}, 20000);

test("restart is a fresh process and does not replay expressions", async () => {
  const first = await start();
  try { await first.request("eval", { code: "const ephemeral = 7" }); }
  finally { await first.stop(); }
  const second = await start();
  try { expect((await second.request("eval", { code: "typeof ephemeral" })).text).toBe("'undefined'"); }
  finally { await second.stop(); }
}, 15000);

test("edited commands replace console values without rerunning earlier side effects", async () => {
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    const original = "var splitters = ['b', 'dy']; var input = 'aabccdyee'; " +
      "input.split(new RegExp('(' + splitters.join('|') + ')'))";
    const edited = "var splitters = ['b', 'dy']; var input = 'aabccdyee'; " +
      "input.split(new RegExp('(' + splitters.join('|') + ')')).filter(Boolean)";
    await run(original);
    const repeated = await run(edited);
    expect(repeated.text).toContain("'aa'");
    expect(repeated.text).toContain("'dy'");
    expect((await run("splitters.join(',')")).text).toBe("'b,dy'");
    expect((await run("40 + 2")).text).toBe("42");
    await run("var arr = [1, 2]");
    await run("var arr = 42");
    expect((await run("arr")).text).toBe("42");
    await run("globalThis.runs = 0");
    expect((await run("var counted = ++runs; counted")).text).toBe("1");
    expect((await run("var counted = ++runs; counted")).text).toBe("2");
    expect((await run("runs")).text).toBe("2");
    await run("globalThis.sideEffect = 0");
    await expect(run("sideEffect++; throw new SyntaxError(\"Can't create duplicate variable: 'splitters'\")"))
      .rejects.toThrow("Can't create duplicate variable");
    expect((await run("sideEffect")).text).toBe("1");
    await expect(run("const broken = ;")).rejects.toThrow();
  } finally { await runtime.stop(); }
}, 15000);

test("reload one context file keeps console state and refreshes its exports", async () => {
  const directory = mkdtempSync(join(tmpdir(), "js-console-reload-"));
  const main = join(directory, "main.ts");
  const extra = join(directory, "extra.ts");
  const idePath = (path) => path.replaceAll("\\", "/");
  writeFileSync(main, "export const first = 1;");
  writeFileSync(extra, "export const extra = 'old';");
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    await runtime.request("load", { path: idePath(main) });
    await runtime.request("add_file", { path: idePath(extra) });
    await run("const retained = 7");
    writeFileSync(main, "export const first = 2; export const added = 42;");
    const loaded = await runtime.request("reload_file", { path: idePath(main) });
    expect(loaded.names).toEqual(["added", "first"]);
    expect((await run("[first, added, retained].join(',')")).text).toBe("'2,42,7'");
    writeFileSync(extra, "export const extra = 'new'; export const another = 9;");
    const reloaded = await runtime.request("reload_file", { path: idePath(extra) });
    expect(reloaded.file.bindings).toEqual([
      { exported: "another", local: "another" }, { exported: "extra", local: "extra" },
    ]);
    expect((await run("[extra, another, retained].join(',')")).text).toBe("'new,9,7'");
  } finally {
    await runtime.stop();
    if (directory.startsWith(tmpdir())) rmSync(directory, { recursive: true, force: true });
  }
}, 15000);

test("reload a file that imports another module", async () => {
  const directory = mkdtempSync(join(tmpdir(), "js-console-dependency-"));
  const main = join(directory, "main.ts");
  const dependency = join(directory, "dependency.ts");
  writeFileSync(dependency, "export const dependent = 'old';");
  writeFileSync(main, "import { dependent } from './dependency'; export const first = dependent;");
  const runtime = await start();
  try {
    const loaded = await runtime.request("load", { path: main.replaceAll("\\", "/") });
    expect(loaded.names).toEqual(["first"]);
    await runtime.request("eval", { code: "const mine = 7" });
    writeFileSync(main, "import { dependent } from './dependency'; export const first = dependent; export const added = 'new';");
    const reloaded = await runtime.request("reload_file", { path: main.replaceAll("\\", "/") });
    expect(reloaded.names).toEqual(["added", "first"]);
    expect((await runtime.request("eval", { code: "added" })).text).toBe("'new'");
  } finally {
    await runtime.stop();
    if (directory.startsWith(tmpdir())) rmSync(directory, { recursive: true, force: true });
  }
}, 15000);

test("active file owns short export names and files remain available by qualified name", async () => {
  const directory = mkdtempSync(join(tmpdir(), "js-console-names-"));
  const left = join(directory, "left", "shared.ts");
  const right = join(directory, "right", "shared.ts");
  mkdirSync(join(directory, "left"));
  mkdirSync(join(directory, "right"));
  writeFileSync(left, "export const value = 'left';");
  writeFileSync(right, "export const value = 'right';");
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    await runtime.request("load", { path: left });
    const pinned = await runtime.request("add_file", { path: right });
    expect((await run("value")).text).toBe("'left'");
    expect((await run("globalThis['left/shared.ts'].value + globalThis['right/shared.ts'].value")).text).toBe("'leftright'");
    await runtime.request("load", { path: right });
    await runtime.request("add_file", { path: left, bindings: pinned.bindings });
    expect((await run("value")).text).toBe("'right'");
    expect((await run("globalThis['left/shared.ts'].value + globalThis['right/shared.ts'].value")).text).toBe("'leftright'");
  } finally {
    await runtime.stop();
    if (directory.startsWith(tmpdir())) rmSync(directory, { recursive: true, force: true });
  }
}, 15000);

test("static import plans: builtins, aliases, live bindings, context resolution and collisions", async () => {
  const runtime = await start();
  const run = (code, imports = []) => runtime.request("eval", { code, imports });
  const declaration = (specifier, imported, local) => ({ specifier, bindings: [{ imported, local }] });
  try {
    const path = declaration("path", "default", "path");
    expect((await run("path.join(process.cwd(), 'texts').endsWith('texts')", [path])).text).toBe("true");
    expect((await run("path.basename('/a/b')")).text).toBe("'b'");
    expect((await run("path.basename('/a/c')", [path])).text).toBe("'c'");
    expect((await run("combine('a', 'b') === path.join('a', 'b')", [declaration("node:path", "join", "combine")])).text).toBe("true");
    expect((await run("typeof files.readFile", [declaration("node:fs", "*", "files")])).text).toBe("'function'");
    expect((await run("42", [{ specifier: "node:assert", bindings: [] }])).text).toBe("42");
    await runtime.request("load", { path: fixture("a.ts") });
    const explicit = [declaration("./a", "default", "answer"), declaration("./a", "count", "liveCount"), declaration("./a", "increment", "increment")];
    expect((await run("increment(); [answer, liveCount, count].join(',')", explicit)).text).toBe("'42,1,1'");
    await runtime.request("load", { path: fixture("b.ts") });
    expect((await run("increment(); liveCount")).text).toBe("2");
    await run("const mine = 7");
    await expect(run("mine", [declaration("path", "default", "mine")])).rejects.toThrow("already in use");
    expect((await run("mine")).text).toBe("7");
    await expect(run("process", [declaration("path", "default", "process")])).rejects.toThrow("already in use");
    await expect(run("42", [declaration("path", "default", "notCommitted"), declaration("node:path", "noSuchExport", "missingExport")])).rejects.toThrow("has no export");
    expect((await run("typeof notCommitted")).text).toBe("'undefined'");
    await expect(run("42", [declaration("./absent", "default", "absent")])).rejects.toThrow();
    await run("path = 'mine'");
    await expect(run("path", [path])).rejects.toThrow("already in use");
    expect((await run("path")).text).toBe("'mine'");
    expect((await run("6 * 7")).text).toBe("42");
  } finally { await runtime.stop(); }
}, 20000);

test("errors that escape user code are reported and the runtime keeps its state", async () => {
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    await run("var kept = 42");
    expect((await run("Promise.reject(new Error('unhandled rejection')); 'scheduled'")).text).toBe("'scheduled'");
    expect((await run("setTimeout(() => { throw new Error('timer failure') }, 0); 'scheduled'")).text).toBe("'scheduled'");
    for (let i = 0; i < 100 && !runtime.stdout().includes("timer failure"); i++) await Bun.sleep(10);
    expect(runtime.stdout()).toContain("Uncaught (in promise) Error: unhandled rejection");
    expect(runtime.stdout()).toContain("Uncaught Error: timer failure");
    expect((await run("kept")).text).toBe("42");
    await expect(run("throw 'plain string'")).rejects.toThrow("plain string");
  } finally { await runtime.stop(); }
}, 15000);

test("a pending await does not block later commands, and each result keeps its own error", async () => {
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    const slow = run("await new Promise((resolve) => setTimeout(() => resolve('slow'), 300))");
    const failing = run("await new Promise((_, reject) => setTimeout(() => reject(new Error('second failed')), 100))");
    expect((await run("40 + 2")).text).toBe("42");
    expect((await runtime.request("ping")).version).toBeString();
    await expect(failing).rejects.toThrow("second failed");
    expect((await slow).text).toBe("'slow'");
    run("await new Promise(() => {})").catch(() => {});
    expect((await run("'still responsive'")).text).toBe("'still responsive'");
  } finally { await runtime.stop(); }
}, 15000);

test("context files are not executed until the console reads one of their bindings", async () => {
  const directory = mkdtempSync(join(tmpdir(), "js-console-lazy-"));
  const main = join(directory, "main.ts");
  const other = join(directory, "other.ts");
  writeFileSync(join(directory, "shared.ts"), "export const shared: number = 5;");
  writeFileSync(main, "globalThis.mainRuns = (globalThis.mainRuns ?? 0) + 1;\n" +
    "export function twice(value: number): number { return value * 2; }\nexport * from './shared';");
  writeFileSync(other, "globalThis.otherRuns = (globalThis.otherRuns ?? 0) + 1;\nexport const other = 'o';\n" +
    "export const items = [1, 2];\nexport const isList = (value: unknown) => value instanceof Array;");
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    const loaded = await runtime.request("load", { path: main.replaceAll("\\", "/") });
    expect(loaded.names).toEqual(["shared", "twice"]);
    await runtime.request("add_file", { path: other.replaceAll("\\", "/") });
    expect((await run("[typeof globalThis.mainRuns, typeof globalThis.otherRuns].join()")).text).toBe("'undefined,undefined'");
    expect((await run("twice(shared)")).text).toBe("10");
    expect((await run("twice(1); globalThis.mainRuns")).text).toBe("1");
    expect((await run("typeof globalThis.otherRuns")).text).toBe("'undefined'");
    // Console code and project modules share one realm, as in DevTools.
    expect((await run("[items instanceof Array, isList([3])].join()")).text).toBe("'true,true'");
    await runtime.request("reload_file", { path: main.replaceAll("\\", "/") });
    expect((await run("globalThis.mainRuns")).text).toBe("1");
    expect((await run("twice(2); globalThis.mainRuns")).text).toBe("2");
    writeFileSync(main, "export const broken: number = ;");
    await expect(runtime.request("reload_file", { path: main.replaceAll("\\", "/") })).rejects.toThrow();
    expect((await run("twice(3)")).text).toBe("6");
  } finally {
    await runtime.stop();
    if (directory.startsWith(tmpdir())) rmSync(directory, { recursive: true, force: true });
  }
}, 15000);

test("non-exported top-level declarations listed by the IDE are available without changing source positions", async () => {
  const directory = mkdtempSync(join(tmpdir(), "js-console-declarations-"));
  const main = join(directory, "main.ts").replaceAll("\\", "/");
  const early = join(directory, "early.ts").replaceAll("\\", "/");
  const legacy = join(directory, "legacy.js").replaceAll("\\", "/");
  writeFileSync(main, "type Unit = number;\nlet counter: Unit = 1;\nfunction bump(): Unit { return ++counter; }\n" +
    "class Hidden { value = 'h' }\nexport const visible = 'v';\nexport function fail() { throw new Error('where'); }");
  writeFileSync(early, "function secret() { return 's'; }\nexport const open = 1;");
  writeFileSync(legacy, "function helper() { return 1; }\nmodule.exports = { helper };");
  const runtime = await start();
  const run = (code) => runtime.request("eval", { code });
  try {
    const loaded = await runtime.request("load", { path: main, declared: ["counter", "bump", "Hidden", "visible", "fail"] });
    expect(loaded.names).toEqual(["Hidden", "bump", "counter", "fail", "visible"]);
    expect((await run("[bump(), counter, new Hidden().value, visible].join()")).text).toBe("'2,2,h,v'");
    await expect(run("fail()")).rejects.toThrow(/main\.ts:6:/);
    await run(`await import(${JSON.stringify(early)})`);
    const added = await runtime.request("add_file", { path: early, declared: ["secret", "open"] });
    expect(added.hidden).toEqual(["secret"]);
    await expect(runtime.request("add_symbol", { path: early, imported: "secret", declared: ["secret", "open"] }))
      .rejects.toThrow("Restart Runtime");
    const cjs = await runtime.request("add_file", { path: legacy, declared: ["helper"] });
    expect((await run("legacy_default.helper()")).text).toBe("1");
    expect(cjs.hidden).toEqual([]);
  } finally {
    await runtime.stop();
    if (directory.startsWith(tmpdir().replaceAll("\\", "/")) || directory.startsWith(tmpdir())) rmSync(directory, { recursive: true, force: true });
  }
}, 15000);

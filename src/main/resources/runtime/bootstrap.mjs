import repl from "node:repl";
import vm from "node:vm";
import net from "node:net";
import { existsSync, readFileSync } from "node:fs";
import { PassThrough, Writable } from "node:stream";
import { Console } from "node:console";
import { StringDecoder } from "node:string_decoder";
import { formatWithOptions, inspect } from "node:util";
import { AsyncLocalStorage } from "node:async_hooks";
import { fileURLToPath, pathToFileURL } from "node:url";
import { basename, dirname, extname, isAbsolute, resolve } from "node:path";
import Module, { builtinModules } from "node:module";

// The IDE owns the loopback listener. User stdout/stderr are never a protocol.
const port = Number(process.env.BUN_CONSOLE_PORT);
const token = process.env.BUN_CONSOLE_TOKEN;
if (!Number.isInteger(port) || port < 1 || !token) throw new Error("Missing console connection settings");
const [major, minor] = (process.versions.bun ?? "0.0").split(".").map(Number);
if (major < 1 || (major === 1 && minor < 4)) throw new Error("Bun Console requires Bun 1.4 or newer");
delete process.env.BUN_CONSOLE_PORT;
delete process.env.BUN_CONSOLE_TOKEN;

// Console output travels on the control socket, so the IDE shows it in order
// with command results (as in DevTools). Output of child processes that
// inherit Bun's file descriptors still arrives through the process pipes.
function sendOutput(text, level) { if (text) send({ event: "output", text, level, error: level === "error" }); }
for (const [stream, level] of [[process.stdout, "log"], [process.stderr, "error"]]) {
  const decoder = new StringDecoder("utf8");
  stream.write = (chunk, encoding, callback) => {
    if (typeof encoding === "function") { callback = encoding; encoding = undefined; }
    try {
      sendOutput(typeof chunk === "string" ? chunk : decoder.write(Buffer.from(chunk)), level);
    } catch {}
    if (typeof callback === "function") queueMicrotask(callback);
    return true;
  };
}
const consoleStream = (level) => new Writable({
  write(chunk, _, callback) { sendOutput(String(chunk), level); callback(); },
});
globalThis.console = new Console({ stdout: consoleStream("log"), stderr: consoleStream("error"), colorMode: false });
// DevTools shows warnings apart from errors (yellow rather than red).
const warnings = new Console({ stdout: consoleStream("warn"), stderr: consoleStream("warn"), colorMode: false });
globalThis.console.warn = (...args) => warnings.warn(...args);

// Width of the IDE's output in characters, sent with every command.
let columns = 120;
// The output is plain text, not an expandable tree as in DevTools, so values are
// printed completely: no depth limit, no "… more items", no cut strings.
const COMPLETE = { colors: false, depth: Infinity, maxArrayLength: Infinity, maxStringLength: Infinity };
// Only a pathological size is cut, and visibly, to keep the IDE responsive.
const MAX_OUTPUT = 5_000_000;
// A value that fits the output width is printed on one line; a longer one is
// laid out for the actual width (not a terminal's 80 columns).
function fitToWidth(format) {
  let text;
  try {
    const line = format({ ...COMPLETE, compact: true, breakLength: Infinity });
    text = line.length <= columns && !line.includes("\n") ? line : format({ ...COMPLETE, breakLength: columns });
  } catch (error) {
    // Structures nested thousands of levels deep overflow the formatter's stack.
    if (!(error instanceof RangeError)) throw error;
    text = format({ ...COMPLETE, depth: 64, breakLength: columns }) + "\n(nested deeper than 64 levels; deeper levels are not shown)";
  }
  return capped(text);
}
function capped(text) {
  return text.length <= MAX_OUTPUT ? text
    : `${text.slice(0, MAX_OUTPUT)}\n… output cut at ${MAX_OUTPUT.toLocaleString("en")} of ${text.length.toLocaleString("en")} characters`;
}
// util.inspect lays long arrays of primitives out in at most 15 columns;
// use the whole width instead (numbers right-aligned, as util.inspect does).
function isPrimitiveArray(value) {
  if (!Array.isArray(value) || value.length < 2 || Object.keys(value).length !== value.length) return false;
  return value.every((item) => item === null || (typeof item !== "object" && typeof item !== "function"));
}
function arrayGrid(value) {
  const items = value.map((item) => inspect(item, COMPLETE));
  const width = Math.max(...items.map((item) => item.length));
  const perRow = Math.max(1, Math.floor((columns - 2) / (width + 2)));
  const rows = [];
  for (let start = 0; start < items.length; start += perRow) {
    rows.push(items.slice(start, start + perRow).map((item, index) => {
      const number = typeof value[start + index] === "number" || typeof value[start + index] === "bigint";
      return number ? item.padStart(width) : item.padEnd(width);
    }).join(", ").trimEnd());
  }
  return `[\n  ${rows.join(",\n  ")}\n]`;
}
function formatAny(value, format) {
  if (isPrimitiveArray(value)) {
    const line = format({ ...COMPLETE, compact: true, breakLength: Infinity });
    if (line.length > columns) return capped(arrayGrid(value));
  }
  return fitToWidth(format);
}
for (const method of ["log", "info", "debug", "warn", "error"]) {
  const print = globalThis.console[method];
  globalThis.console[method] = (...args) => print(args.length === 1
    ? formatAny(args[0], (options) => formatWithOptions(options, args[0]))
    : fitToWidth((options) => formatWithOptions(options, ...args)));
}
// As in DevTools, console.clear() empties the console.
globalThis.console.clear = () => send({ event: "clear" });

// Stack frames of the console itself (this file, the REPL, the console input line) say nothing
// about the user's code; frames in project files stay.
const SELF = fileURLToPath(import.meta.url).toLowerCase();
const INTERNAL_FRAME = /^\s+at .*(node:repl|node:async_hooks|runInThisContext|\(native|native:|bun-console:\d)/;
function trimStack(stack) {
  return stack.split("\n").filter((line) => !/^\s+at /.test(line) ||
    !(line.toLowerCase().includes(SELF) || INTERNAL_FRAME.test(line))).join("\n");
}

function formatError(error) {
  try {
    return error instanceof Error || typeof error?.stack === "string" ? trimStack(String(error.stack ?? error)) : inspect(error);
  } catch { return "Unprintable error"; }
}

// Like a DevTools page, the runtime survives errors that escape user code.
function reportUncaught(prefix, error) {
  try { process.stderr.write(`${prefix} ${formatError(error)}\n`); } catch {}
}
process.on("uncaughtException", (error) => reportUncaught("Uncaught", error));
process.on("unhandledRejection", (reason) => reportUncaught("Uncaught (in promise)", reason));

// node:repl reports runtime errors through handleError rather than the eval
// callback. The async scope tells which of the concurrent evaluations failed.
const evaluationScope = new AsyncLocalStorage();
const input = new PassThrough();
const output = new PassThrough();
output.on("data", (chunk) => process.stdout.write(chunk));
const initialGlobals = new Set(Object.getOwnPropertyNames(globalThis));
// Like DevTools, console code shares the realm of the modules it calls:
// arrays, errors and classes from project files pass `instanceof` checks.
const server = repl.start({
  input, output, prompt: "", terminal: false, useGlobal: true,
  handleError(error) {
    const evaluation = evaluationScope.getStore();
    if (evaluation && !evaluation.settled) evaluation.reject(error);
    else reportUncaught("Uncaught", error);
    return "ignore";
  },
});
// node:repl preloads module aliases such as `path` and `fs`. They are not JS
// globals: leave those names free for explicit imports and current-file exports.
for (const name of builtinModules) {
  if (!initialGlobals.has(name) && Object.getOwnPropertyDescriptor(server.context, name)?.configurable) {
    delete server.context[name];
  }
}
// Globals that exist before any console input; later ones are the user's (for completion).
// Dynamic import() in console input (rewritten to $bcImp by the IDE) resolves like a
// static import there: relative to the context file or the project folder, not to
// the REPL's internals. A bare file name gets a hint instead of "Cannot find package".
Object.defineProperty(globalThis, "$bcImp", {
  configurable: false, enumerable: false, writable: false,
  value(specifier, options) {
    const base = currentFile ? dirname(resolve(currentFile)) : process.cwd();
    let target = specifier;
    if (typeof specifier === "string" && !/^[a-z][a-z0-9+.-]*:/i.test(specifier)) {
      try {
        const resolved = Bun.resolveSync(specifier, base);
        target = isAbsolute(resolved) ? pathToFileURL(resolved).href : resolved;
      } catch (error) {
        if (!specifier.startsWith(".") && !isAbsolute(specifier) && existsSync(resolve(base, specifier))) {
          const where = currentFile ? `next to ${basename(currentFile)}` : "in the project folder";
          return Promise.reject(new Error(`Cannot find package '${specifier}'. To import the file ${where}, write './${specifier}'`));
        }
        return Promise.reject(error);
      }
    }
    return options === undefined ? import(target) : import(target, options);
  },
});
const baselineGlobals = new Set(Object.getOwnPropertyNames(globalThis));
const IDENTIFIER = /^[$_\p{ID_Start}][$\u200c\u200d\p{ID_Continue}]*$/u;
const bindings = new Map();
const addedFiles = new Map();
const fileNamespaces = new Map();
const qualifiedProperties = new Map();
const modules = new Map();
let currentFile = null;
const socket = net.createConnection({ host: "127.0.0.1", port });
socket.setEncoding("utf8");
socket.on("error", (error) => { console.error(error.message); process.exit(1); });
socket.on("close", () => process.exit(0));
socket.on("connect", () => send({ event: "ready", token, version: process.versions.bun }));

const DEFERRED = Symbol("deferred result");
let pending = "";
let queue = Promise.resolve();
socket.on("data", (chunk) => {
  pending += chunk;
  if (pending.length > 2_000_000) { socket.destroy(); return; }
  let end;
  while ((end = pending.indexOf("\n")) >= 0) {
    const line = pending.slice(0, end);
    pending = pending.slice(end + 1);
    let request;
    try { request = JSON.parse(line); }
    catch (error) { send({ ok: false, error: `Invalid request: ${formatError(error)}` }); continue; }
    // Answered outside the queue: a late reply means the JavaScript thread is busy.
    if (request?.op === "ping") { send({ id: request.id, ok: true, version: process.versions.bun }); continue; }
    queue = queue.then(() => handle(request));
  }
});

async function handle(request) {
  const fail = (error) => send({ id: request?.id, ok: false, error: formatError(error),
    ...(error instanceof NotRunError ? { notRun: { path: error.path, name: error.binding } } : {}) });
  try {
    const result = await dispatch(request);
    // An evaluation leaves the queue once its synchronous part has run, so a
    // pending await does not block later commands (as in DevTools).
    if (result?.[DEFERRED]) result[DEFERRED].then((value) => send({ id: request.id, ok: true, ...value }), fail);
    else send({ id: request.id, ok: true, ...result });
  } catch (error) { fail(error); }
}

function send(message) {
  try { socket.write(`${JSON.stringify(message)}\n`); }
  catch (error) { reportUncaught("Bun Console could not send a reply:", error); }
}

function evaluate(source, withoutRunning = false) {
  return new Promise((resolve, reject) => {
    const evaluation = {
      settled: false,
      withoutRunning,
      resolve(value) { if (!evaluation.settled) { evaluation.settled = true; resolve(value); } },
      reject(error) { if (!evaluation.settled) { evaluation.settled = true; reject(error); } },
    };
    evaluationScope.run(evaluation, () => {
      try {
        server.eval(`${source}\n`, server.context, "bun-console", (error, value) => {
          if (error) evaluation.reject(error);
          else evaluation.resolve(value);
        });
      } catch (error) { evaluation.reject(error); }
    });
  });
}

/** Names console code has defined on the global object, for IDE completion. */
function userGlobals() {
  const names = [];
  for (const name of Object.getOwnPropertyNames(globalThis)) {
    if (baselineGlobals.has(name) || !IDENTIFIER.test(name)) continue;
    const binding = bindings.get(name);
    if (binding && ownsBinding(name, binding)) continue;
    names.push(name);
    if (names.length >= 5000) break;
  }
  return names;
}

// A module namespace prints as "Module { … }", as in DevTools, instead of util.inspect's
// "Module <[Object: null prototype] {}> [Module] { … }".
function moduleView(value) {
  if (Object.prototype.toString.call(value) !== "[object Module]") return undefined;
  const view = {};
  for (const key of Object.keys(value)) {
    try { view[key] = value[key]; } catch { view[key] = Symbol.for("<uninitialized>"); }
  }
  return view;
}

function render(value) {
  const module = moduleView(value);
  if (module) return "Module " + render(module);
  try {
    return formatAny(value, (options) => inspect(value, { ...options, customInspect: false }));
  } catch (error) { return `[Uninspectable value: ${formatError(error)}]`; }
}

function isIdentifier(name) {
  if (!/^[$_\p{ID_Start}][$‌‍\p{ID_Continue}]*$/u.test(name)) return false;
  // Parsing a declaration rejects reserved words without modifying the context.
  try { new vm.Script(`let ${name};`); return true; } catch { return false; }
}

const globalEval = eval;
function exists(name) {
  if (name in server.context) return true;
  // Indirect eval sees console `let`/`const`/`class` bindings of the global scope.
  try { globalEval(name); return true; }
  catch (error) {
    if (error?.name !== "ReferenceError") return true;
    // typeof on an absent name succeeds, but throws for a lexical TDZ binding.
    try { globalEval(`typeof ${name}`); return false; }
    catch { return true; }
  }
}

// Context files are read, not run. A module's top-level code executes only
// when console code first reads one of its bindings.
const LOADERS = { ".ts": "ts", ".mts": "ts", ".cts": "ts", ".tsx": "tsx", ".jsx": "jsx" };
const transpilers = new Map();
const STAR_EXPORT = /^\s*export\s*\*\s*from\s*(["'])([^"'\n]+)\1/gm;

function scanExports(path, seen = new Set()) {
  const key = resolve(path);
  if (seen.has(key)) return new Set();
  seen.add(key);
  const loader = LOADERS[extname(path).toLowerCase()] ?? "jsx";
  let transpiler = transpilers.get(loader);
  if (!transpiler) transpilers.set(loader, transpiler = new Bun.Transpiler({ loader }));
  const source = readFileSync(path, "utf8");
  const names = new Set(transpiler.scan(source).exports);
  // Bun exposes a CommonJS module.exports object as the default export.
  if (COMMON_JS.test(source)) names.add("default");
  // `export * from` does not list names; follow those modules statically too.
  for (const [, , specifier] of source.matchAll(STAR_EXPORT)) {
    try {
      const target = Bun.resolveSync(specifier, dirname(path));
      if (!isAbsolute(target)) continue;
      for (const name of scanExports(target, seen)) if (name !== "default") names.add(name);
    } catch {}
  }
  return names;
}

// Non-exported top-level declarations. The IDE lists a context file's
// declarations; a load hook appends `export { … }` for the unexported ones after
// the last line, so source positions (errors, breakpoints) stay the same.
const DECLARATION_LOADERS = { ".ts": "ts", ".mts": "ts", ".tsx": "tsx", ".jsx": "jsx", ".js": "jsx", ".mjs": "jsx" };
const COMMON_JS = /\bmodule\.exports\b|\bexports\.[\w$]+\s*=/;
const hooked = new Set();
const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// A CommonJS file would stop being CommonJS with an export statement (and a Bun load hook
// turns any file into an ES module). Node's CommonJS compile hook appends getters for its
// top-level names to a registry instead; module.exports stays exactly as the file sets it.
const COMMON_JS_EXTENSIONS = [".cjs", ".js"];
const commonJsKey = (path) => process.platform === "win32" ? resolve(path).toLowerCase() : resolve(path);
const commonJsRecords = new Map();
const commonJsLocals = new Map();
Object.defineProperty(globalThis, Symbol.for("bun-console.commonjs-locals"), {
  value: (file, locals) => commonJsLocals.set(commonJsKey(file), locals),
});
for (const extension of COMMON_JS_EXTENSIONS) {
  const original = Module._extensions[extension] ?? Module._extensions[".js"];
  Module._extensions[extension] = function (module, filename) {
    const record = commonJsRecords.get(commonJsKey(filename));
    if (!record) return original.call(this, module, filename);
    const source = readFileSync(filename, "utf8");
    const locals = record.locals ?? [];
    record.exposed = new Set(locals);
    record.loadedSource = source;
    const getters = locals.map((name) => `get ${name}() { return ${name}; }`).join(", ");
    module._compile(locals.length
      ? `${source}\n;globalThis[Symbol.for("bun-console.commonjs-locals")]?.(${JSON.stringify(filename)}, { ${getters} });\n`
      : source, filename);
  };
}

function exposeDeclarations(record, declared) {
  if (declared == null) return;
  if (!Array.isArray(declared)) throw new Error("Invalid declarations");
  const extension = extname(record.path).toLowerCase();
  const exported = scanExports(record.path);
  const source = readFileSync(record.path, "utf8");
  // The IDE lists declarations from the editor, which can be ahead of the file
  // on disk; only names that the saved file contains can be exposed.
  const names = [...new Set(declared)].filter((name) => typeof name === "string" && !exported.has(name) &&
    isIdentifier(name) && new RegExp(`(^|[^$\\w])${escapeRegExp(name)}($|[^$\\w])`).test(source));
  const loader = DECLARATION_LOADERS[extension];
  if (!loader || COMMON_JS.test(source)) {
    // TypeScript compiled to CommonJS (.cts) has no such hook: only its module.exports is available.
    record.locals = COMMON_JS_EXTENSIONS.includes(extension) ? names : [];
    if (record.locals.length) commonJsRecords.set(commonJsKey(record.path), record);
    return;
  }
  record.locals = names;
  if (!record.locals.length || hooked.has(record.moduleId)) return;
  hooked.add(record.moduleId);
  const file = resolve(record.path);
  const filter = new RegExp(`^${escapeRegExp(file)}$`, process.platform === "win32" ? "i" : "");
  Bun.plugin({
    name: `bun-console declarations: ${file}`,
    setup(build) {
      build.onLoad({ filter }, ({ path }) => {
        const source = readFileSync(path, "utf8");
        const locals = record.locals ?? [];
        record.exposed = new Set(locals);
        record.loadedSource = source;
        return { contents: locals.length ? `${source}\n;export { ${locals.join(", ")} };\n` : source, loader };
      });
    },
  });
}

/**
 * The IDE also lists the declarations of the context file's project imports.
 * Preparing them before anything runs keeps their names available when they
 * are first loaded as a dependency rather than as a console context file.
 */
function exposeDependencies(dependencies) {
  if (dependencies == null) return;
  if (typeof dependencies !== "object" || Array.isArray(dependencies)) throw new Error("Invalid dependency declarations");
  for (const [path, declared] of Object.entries(dependencies)) {
    try { exposeDeclarations(moduleRecord(path), declared); } catch {} // A missing or broken dependency is reported when it loads.
  }
}

/**
 * Exports plus exposed declarations. Declarations of a module that was loaded
 * before they were listed are "hidden": still bound, but reading one explains
 * why it is unavailable instead of a bare ReferenceError.
 */
function moduleNames(record) {
  const loaded = !!require.cache[resolve(record.path)];
  // A loaded module is the truth until it is reloaded; reading it from the cache runs nothing.
  const names = loaded
    ? new Set(Object.keys(readModule(record)))
    : scanExports(record.path);
  const hidden = [];
  // A file edited after it was loaded gets its new names when it is reloaded;
  // they are not "hidden" by a dependency that loaded it first.
  const stale = loaded && record.loadedSource !== undefined && record.loadedSource !== readFileSync(record.path, "utf8");
  for (const name of record.locals ?? []) {
    if (!loaded || record.exposed?.has(name)) names.add(name);
    else if (!stale) { hidden.push(name); names.add(name); }
  }
  record.unavailable = new Set(hidden);
  return { names: [...names].sort(), hidden };
}

function moduleRecord(path) {
  const moduleId = pathToFileURL(path).href;
  let record = modules.get(moduleId);
  if (!record) modules.set(moduleId, record = { path, moduleId, namespace: undefined });
  return record;
}

function readModule(record) {
  if (!record.namespace) {
    // Bun's synchronous require shares ES module instances with import(). On
    // Windows it needs the native path: `D:/…` fails for files created after Bun started.
    // The source this instance runs, unless another module loaded it earlier.
    const before = require.cache[resolve(record.path)] ? undefined : readFileSync(record.path, "utf8");
    const loaded = require(resolve(record.path));
    record.loadedSource ??= before;
    // A CommonJS file yields module.exports; present it like import() does, with its exposed top-level names.
    if (Object.prototype.toString.call(loaded) === "[object Module]") record.namespace = loaded;
    else {
      const view = { ...(loaded !== null && typeof loaded === "object" ? loaded : {}) };
      const locals = commonJsLocals.get(commonJsKey(record.path));
      for (const name of Object.keys(locals ?? {})) {
        if (!(name in view)) Object.defineProperty(view, name, { enumerable: true, get: () => locals[name] });
      }
      view.default = loaded;
      record.namespace = Object.freeze(view);
    }
    reconcile(record);
  }
  return record.namespace;
}

function forget(record) {
  delete require.cache[resolve(record.path)];
  record.namespace = undefined;
  record.loadedSource = undefined;
}

// Statically scanned names can differ from the evaluated module (for example
// a CommonJS file). Align automatic bindings once the real namespace exists.
function reconcile(record) {
  const namespace = record.namespace;
  if (namespace == null || typeof namespace !== "object") return;
  for (const [name, binding] of bindings) {
    if (binding.moduleId !== record.moduleId || binding.imported === "*" || !ownsBinding(name, binding)) continue;
    // CommonJS candidates and hidden declarations stay bound so that reading them explains why.
    if (record.unavailable?.has(binding.imported)) continue;
    if ((binding.origin === "current" || binding.origin === "file") && !(binding.imported in namespace)) {
      delete server.context[name];
      bindings.delete(name);
    }
  }
  if (currentFile === record.path) {
    for (const name of Object.keys(namespace)) {
      if (isIdentifier(name) && !exists(name)) bind(name, reader(record, name), "current", record.moduleId, name);
    }
  }
}

/**
 * Code run from the editor (Run Selection) never runs a context file just to read one of its
 * names: the IDE asks first. A file that already ran, or that another module loaded, is used as is.
 */
class NotRunError extends ReferenceError {
  constructor(record, imported) {
    super(`${imported} is from ${basename(record.path)}, which has not run yet`);
    this.path = record.path;
    this.binding = imported;
    this.stack = `ReferenceError: ${this.message}`; // the console's own frames are noise here
  }
}
function needsRun(record) {
  return evaluationScope.getStore()?.withoutRunning === true && !record.namespace && !require.cache[resolve(record.path)];
}

// The skeleton of a file that has not run: its declarations, built by the IDE with the rest of
// the file turned into spaces. It loads as a module next to the file (imports resolve as there),
// under a name that does not exist on disk, and a new version replaces it when the file changes.
const SKELETON = /\.bun-console-skeleton\d+$/;
const skeletonSources = new Map();
Bun.plugin({
  name: "bun-console skeletons",
  setup(build) {
    build.onResolve({ filter: SKELETON }, ({ path }) => ({ path }));
    build.onLoad({ filter: SKELETON }, ({ path }) => ({
      contents: skeletonSources.get(path) ?? "",
      loader: LOADERS[extname(path.replace(SKELETON, "")).toLowerCase()] ?? "jsx",
    }));
  },
});

function applySkeleton(skeleton) {
  if (skeleton == null) return;
  if (typeof skeleton.path !== "string" || typeof skeleton.source !== "string" || !Array.isArray(skeleton.names)) {
    throw new Error("Invalid skeleton");
  }
  const record = moduleRecord(skeleton.path);
  if (record.skeletonSource === skeleton.source) return;
  if (record.skeletonPath) { delete require.cache[record.skeletonPath]; skeletonSources.delete(record.skeletonPath); }
  record.skeletonVersion = (record.skeletonVersion ?? 0) + 1;
  record.skeletonPath = `${resolve(record.path)}.bun-console-skeleton${record.skeletonVersion}`;
  record.skeletonSource = skeleton.source;
  record.skeletonNames = new Set(skeleton.names);
  record.skeleton = undefined;
  skeletonSources.set(record.skeletonPath, skeleton.source);
}

/** A name of a file that has not run: its value in the skeleton, or a question to the IDE. */
function skeletonValue(record, imported) {
  if (!record.skeletonNames?.has(imported)) throw new NotRunError(record, imported);
  if (!record.skeleton) {
    try { record.skeleton = require(record.skeletonPath); }
    catch (error) {
      // Not expected: the skeleton is the file's own declarations. Fall back to running the file.
      reportUncaught(`Bun Console could not load the declarations of ${basename(record.path)}:`, error);
      record.skeletonNames = new Set();
      throw new NotRunError(record, imported);
    }
  }
  return record.skeleton[imported];
}

function reader(record, imported) {
  if (imported === "*") return () => {
    if (needsRun(record)) throw new NotRunError(record, basename(record.path));
    return readModule(record);
  };
  return () => {
    if (needsRun(record)) return skeletonValue(record, imported);
    const namespace = readModule(record);
    if (imported in namespace) return namespace[imported];
    const file = basename(record.path);
    if (record.unavailable?.has(imported)) {
      throw new ReferenceError(`${imported} is not available: another module loaded ${file} before the console ` +
        "could expose its top-level names. Restart Runtime to use it");
    }
    throw new ReferenceError(`${imported} is not defined: ${file} no longer exports it`);
  };
}

function clearAutomaticFileBindings() {
  for (const [name, binding] of bindings) {
    if (binding.origin !== "current" && binding.origin !== "file") continue;
    if (ownsBinding(name, binding)) delete server.context[name];
    bindings.delete(name);
  }
  addedFiles.clear();
}

function refreshQualifiedProperties() {
  for (const { key, get } of qualifiedProperties.values()) {
    if (Object.getOwnPropertyDescriptor(server.context, key)?.get === get) delete server.context[key];
  }
  qualifiedProperties.clear();
  const paths = [...fileNamespaces.keys()];
  const parts = new Map(paths.map((path) => [path, path.replaceAll("\\", "/").split("/").filter(Boolean)]));
  for (const path of paths) {
    const segments = parts.get(path);
    let key;
    for (let count = 1; count <= segments.length; count++) {
      const candidate = segments.slice(-count).join("/");
      if (paths.some((other) => other !== path && parts.get(other).slice(-count).join("/") === candidate)) continue;
      if (candidate in server.context) continue;
      key = candidate;
      break;
    }
    if (!key) continue; // Never replace a user-owned global property.
    const record = fileNamespaces.get(path);
    const get = () => readModule(record);
    Object.defineProperty(server.context, key, { configurable: true, enumerable: false, get });
    qualifiedProperties.set(path, { key, get });
  }
}

function bind(name, readExport, origin, moduleId, imported) {
  const binding = { origin, moduleId, imported, value: undefined };
  binding.read = readExport;
  binding.get = () => binding.origin === "user" ? binding.value : binding.read();
  binding.set = (next) => { binding.value = next; binding.origin = "user"; };
  Object.defineProperty(server.context, name, {
    enumerable: true, configurable: true, get: binding.get, set: binding.set,
  });
  bindings.set(name, binding);
}

function ownsBinding(name, binding) {
  const descriptor = Object.getOwnPropertyDescriptor(server.context, name);
  return binding && binding.origin !== "user" && descriptor?.get === binding.get && descriptor?.set === binding.set;
}

function sameBinding(name, moduleId, imported) {
  const binding = bindings.get(name);
  return ownsBinding(name, binding) && binding.moduleId === moduleId && binding.imported === imported;
}

async function importModules(imports = []) {
  if (!Array.isArray(imports)) throw new Error("Expected an import list");
  const staged = new Map();
  // Validate all bindings before importing, so a collision cannot overwrite user state.
  const planned = imports.map(({ specifier, bindings: names }) => {
    if (typeof specifier !== "string" || !Array.isArray(names)) throw new Error("Invalid import declaration");
    const resolved = Bun.resolveSync(specifier, currentFile ? dirname(resolve(currentFile)) : process.cwd());
    const moduleId = isAbsolute(resolved) ? pathToFileURL(resolved).href : resolved;
    for (const { imported, local } of names) {
      if (typeof imported !== "string" || !isIdentifier(local)) throw new Error("Invalid import binding");
      if (staged.has(local)) throw new Error(`Duplicate import binding: ${local}`);
      const existing = bindings.get(local);
      if (exists(local) && !(ownsBinding(local, existing) && existing.moduleId === moduleId && existing.imported === imported)) {
        throw new Error(`Cannot import '${local}': the name is already in use; choose an alias`);
      }
      staged.set(local, { moduleId, imported });
    }
    return { moduleId, names };
  });
  // An explicit import statement runs the module, exactly as it would in a file.
  for (const { moduleId, names } of planned) {
    const namespace = await import(moduleId);
    const record = modules.get(moduleId);
    if (record && !record.namespace) { record.namespace = namespace; reconcile(record); }
    for (const { imported, local } of names) {
      if (imported !== "*" && !Object.hasOwn(namespace, imported)) {
        throw new Error(`Module '${moduleId}' has no export '${imported}'`);
      }
      staged.get(local).read = () => imported === "*" ? namespace : namespace[imported];
    }
  }
  // Commit only after every import succeeds. Module side effects follow Bun's module cache.
  for (const [name, { moduleId, imported, read }] of staged) {
    bind(name, read, "import", moduleId, imported);
  }
}

function addSymbol(path, imported, requestedLocal, declared) {
  if (typeof path !== "string" || typeof imported !== "string" ||
      (requestedLocal != null && !isIdentifier(requestedLocal))) throw new Error("Invalid symbol request");
  const record = moduleRecord(path);
  exposeDeclarations(record, declared);
  const { names, hidden } = moduleNames(record);
  if (hidden.includes(imported)) {
    throw new Error(`'${imported}' is not exported and '${basename(path)}' was already loaded by another module; Restart Runtime to use it`);
  }
  if (!names.includes(imported)) {
    throw new Error(`${basename(path)} has no top-level name '${imported}'`);
  }
  let local = requestedLocal ?? imported;
  if (!isIdentifier(local)) throw new Error(`Export '${imported}' needs a valid JavaScript alias`);
  const same = (name) => sameBinding(name, record.moduleId, imported);
  if (same(local) && bindings.get(local).origin === "import") return { local, already: true };
  if (exists(local) && !same(local)) {
    if (requestedLocal != null) throw new Error(`Cannot add '${local}': the name is already in use`);
    let suffix = 2;
    while (exists(`${local}_${suffix}`)) suffix++;
    local = `${local}_${suffix}`;
  }
  bind(local, reader(record, imported), "import", record.moduleId, imported);
  return { local, already: false };
}

function fileDefaultAlias(path) {
  let stem = basename(path).replace(/\.[^.]+$/, "").replace(/[^A-Za-z0-9_$]/g, "_");
  if (!stem || /^[0-9]/.test(stem)) stem = `_${stem}`;
  return `${stem}_default`;
}

/** Bind every export of an added file, keeping previously chosen aliases where possible. */
function bindFile(record, names, requested) {
  const staged = [];
  const taken = new Set();
  const unsupported = [];
  for (const exported of names) {
    const base = exported === "default" ? fileDefaultAlias(record.path) : exported;
    if (!isIdentifier(base)) { unsupported.push(exported); continue; }
    const same = (name) => sameBinding(name, record.moduleId, exported);
    let local = requested.get(exported) ?? base;
    if (requested.has(exported) && (taken.has(local) || (exists(local) && !same(local)))) local = base;
    let suffix = 2;
    while (taken.has(local) || (exists(local) && !same(local))) local = `${base}_${suffix++}`;
    taken.add(local);
    staged.push({ exported, local, existing: same(local) });
  }
  for (const { exported, local, existing } of staged) {
    // A matching explicit import is already available. A matching auto-context
    // binding must become persistent before following another editor file.
    if (existing && bindings.get(local).origin !== "current") continue;
    bind(local, reader(record, exported), "file", record.moduleId, exported);
  }
  const result = { bindings: staged.map(({ exported, local }) => ({ exported, local })), unsupported };
  addedFiles.set(record.moduleId, result);
  return result;
}

/** Expose the editor file's exports under their own names, never replacing an existing name. */
function bindCurrent(record, names) {
  const exposed = [];
  const collisions = [];
  const unsupported = [];
  for (const name of names) {
    if (!isIdentifier(name)) { unsupported.push(name); continue; }
    if (!exists(name)) bind(name, reader(record, name), "current", record.moduleId, name);
    else if (!sameBinding(name, record.moduleId, name)) { collisions.push(name); continue; }
    exposed.push(name);
  }
  return { names: exposed, collisions, unsupported };
}

function addFile(path, requestedBindings, declared) {
  if (typeof path !== "string") throw new Error("Expected a file path");
  const record = moduleRecord(path);
  const previous = addedFiles.get(record.moduleId);
  if (previous) return { ...previous, already: true };
  if (requestedBindings != null && !Array.isArray(requestedBindings)) throw new Error("Invalid file bindings");
  exposeDeclarations(record, declared);
  const { names, hidden } = moduleNames(record);
  const requested = new Map();
  for (const entry of requestedBindings ?? []) {
    if (typeof entry?.exported !== "string" || !isIdentifier(entry.local) || requested.has(entry.exported)) {
      throw new Error("Invalid file binding");
    }
    requested.set(entry.exported, entry.local);
  }
  const result = bindFile(record, names, requested);
  fileNamespaces.set(path, record);
  refreshQualifiedProperties();
  return { ...result, hidden, already: false };
}

function reloadFile(path, declared) {
  if (typeof path !== "string") throw new Error("Expected a file path");
  const record = moduleRecord(path);
  const isCurrent = currentFile === path;
  const previousFile = addedFiles.get(record.moduleId);
  if (!isCurrent && !previousFile) throw new Error("File is not in the console context");

  // Only this file is invalidated; its imported dependencies retain their
  // module state. The new version runs when the console next reads it.
  scanExports(path); // A file that no longer parses keeps the previous bindings.
  exposeDeclarations(record, declared);
  forget(record);
  const { names } = moduleNames(record);
  fileNamespaces.set(path, record);
  refreshQualifiedProperties();
  const available = new Set(names);
  for (const [name, binding] of bindings) {
    if (binding.moduleId !== record.moduleId || !ownsBinding(name, binding)) continue;
    if (binding.imported === "*" || available.has(binding.imported)) {
      binding.read = reader(record, binding.imported);
    } else if (binding.origin === "current" || binding.origin === "file") {
      delete server.context[name];
      bindings.delete(name);
    }
  }
  const current = isCurrent ? bindCurrent(record, names) : { names: [], collisions: [], unsupported: [] };
  const file = previousFile
    ? bindFile(record, names, new Map(previousFile.bindings.map(({ exported, local }) => [exported, local])))
    : null;
  return { ...current, file };
}

function removeFile(path) {
  if (typeof path !== "string") throw new Error("Expected a file path");
  const moduleId = pathToFileURL(path).href;
  const file = addedFiles.get(moduleId);
  if (!file) return { removed: false };
  for (const { exported, local } of file.bindings) {
    const binding = bindings.get(local);
    if (binding?.origin !== "file" || !sameBinding(local, moduleId, exported)) continue;
    delete server.context[local];
    bindings.delete(local);
  }
  addedFiles.delete(moduleId);
  if (currentFile !== path) fileNamespaces.delete(path);
  refreshQualifiedProperties();
  return { removed: true };
}

/**
 * Run File: the file's top-level code runs again now, like `bun file.ts`. Its
 * dependencies keep their module state; console names of the file read the new run.
 */
async function runFile(path, declared) {
  if (typeof path !== "string") throw new Error("Expected a file path");
  const record = moduleRecord(path);
  scanExports(path); // A file that no longer parses keeps the previous run.
  exposeDeclarations(record, declared);
  const source = readFileSync(path, "utf8");
  forget(record);
  // import(), not require(): a file may use top-level await.
  const namespace = await import(record.moduleId);
  record.namespace = namespace;
  record.loadedSource ??= source;
  reconcile(record);
  const names = currentFile === path ? moduleNames(record).names : undefined;
  return { globals: userGlobals(), names };
}

// Code selected in a TypeScript or JSX file runs as console input once compiled.
// Dead code elimination stays off: it would drop the final expression whose value the console prints.
const selectionTranspilers = new Map();
function compileSelection(code, loader) {
  if (loader == null) return code;
  if (!["ts", "tsx", "jsx"].includes(loader)) throw new Error(`Unknown language: ${loader}`);
  let transpiler = selectionTranspilers.get(loader);
  if (!transpiler) selectionTranspilers.set(loader, transpiler = new Bun.Transpiler({ loader, deadCodeElimination: false }));
  return transpiler.transformSync(code);
}

function loadCurrent(path, declared) {
  if (!path) {
    clearAutomaticFileBindings();
    currentFile = null;
    fileNamespaces.clear();
    refreshQualifiedProperties();
    return { names: [], collisions: [], unsupported: [], hidden: [], path: null };
  }
  // Read first: a missing or unparsable file leaves the old context usable.
  const record = moduleRecord(path);
  exposeDeclarations(record, declared);
  const { names, hidden } = moduleNames(record);
  clearAutomaticFileBindings();
  currentFile = path;
  fileNamespaces.clear();
  fileNamespaces.set(path, record);
  refreshQualifiedProperties();
  return { ...bindCurrent(record, names), hidden, path };
}

async function dispatch(request) {
  exposeDependencies(request?.dependencies);
  switch (request?.op) {
    case "eval": {
      if (typeof request.code !== "string") throw new Error("Expected JavaScript source");
      if (Number.isInteger(request.columns)) columns = Math.min(Math.max(request.columns, 40), 1000);
      if (request.withoutRunning === true) applySkeleton(request.skeleton);
      if (request.declarations != null && (!Array.isArray(request.declarations) ||
          request.declarations.some((name) => !isIdentifier(name)))) throw new Error("Invalid console declarations");
      await importModules(request.imports);
      // Console declarations take precedence over automatically exposed file
      // exports. The original module namespace remains under globalThis['file.ts'].
      for (const name of request.declarations ?? []) {
        const binding = bindings.get(name);
        if (!ownsBinding(name, binding)) continue;
        delete server.context[name];
        bindings.delete(name);
      }
      return { [DEFERRED]: evaluate(compileSelection(request.code, request.loader), request.withoutRunning === true)
        .then((value) => ({ text: render(value), globals: userGlobals() })) };
    }
    case "run_file": {
      if (Number.isInteger(request.columns)) columns = Math.min(Math.max(request.columns, 40), 1000);
      return await runFile(request.path, request.declared);
    }
    case "load": return loadCurrent(request.path, request.declared);
    case "add_symbol": return addSymbol(request.path, request.imported, request.local, request.declared);
    case "add_file": return addFile(request.path, request.bindings, request.declared);
    case "reload_file": return reloadFile(request.path, request.declared);
    case "remove_file": return removeFile(request.path);
    case "cached_files": return { paths: Object.keys(require.cache) };
    default: throw new Error(`Unknown console operation: ${request?.op}`);
  }
}

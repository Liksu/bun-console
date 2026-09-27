import repl from "node:repl";
import vm from "node:vm";
import net from "node:net";
import { PassThrough } from "node:stream";
import { inspect } from "node:util";
import { pathToFileURL } from "node:url";
import { basename, dirname, isAbsolute, resolve } from "node:path";
import { builtinModules } from "node:module";

// The IDE owns the loopback listener. User stdout/stderr are never a protocol.
const port = Number(process.env.JS_CONSOLE_PORT);
const token = process.env.JS_CONSOLE_TOKEN;
if (!Number.isInteger(port) || port < 1 || !token) throw new Error("Missing console connection settings");
const [major, minor] = (process.versions.bun ?? "0.0").split(".").map(Number);
if (major < 1 || (major === 1 && minor < 4)) throw new Error("JS Console requires Bun 1.4 or newer");
delete process.env.JS_CONSOLE_PORT;
delete process.env.JS_CONSOLE_TOKEN;

const input = new PassThrough();
const output = new PassThrough();
output.on("data", (chunk) => process.stdout.write(chunk));
let rejectEvaluation = null;
const server = repl.start({
  input, output, prompt: "", terminal: false, useGlobal: false,
  handleError(error) {
    if (rejectEvaluation) rejectEvaluation(error);
    else console.error(error);
    return "ignore";
  },
});
// node:repl preloads module aliases such as `path` and `fs`. They are not JS
// globals: leave those names free for explicit imports and current-file exports.
for (const name of builtinModules) {
  if (!(name in globalThis) && Object.getOwnPropertyDescriptor(server.context, name)?.configurable) {
    delete server.context[name];
  }
}
const bindings = new Map();
const addedFiles = new Map();
const fileNamespaces = new Map();
const qualifiedProperties = new Map();
let currentFile = null;
const socket = net.createConnection({ host: "127.0.0.1", port });
socket.setEncoding("utf8");
socket.on("error", (error) => { console.error(error.message); process.exit(1); });
socket.on("close", () => process.exit(0));
socket.on("connect", () => send({ event: "ready", token, version: process.versions.bun }));

let pending = "";
let queue = Promise.resolve();
socket.on("data", (chunk) => {
  pending += chunk;
  if (pending.length > 2_000_000) { socket.destroy(); return; }
  let end;
  while ((end = pending.indexOf("\n")) >= 0) {
    const line = pending.slice(0, end);
    pending = pending.slice(end + 1);
    queue = queue.then(async () => {
      let request;
      try {
        request = JSON.parse(line);
        const result = await dispatch(request);
        send({ id: request.id, ok: true, ...result });
      } catch (error) {
        send({ id: request?.id, ok: false, error: String(error?.stack ?? error) });
      }
    });
  }
});

function send(message) { socket.write(`${JSON.stringify(message)}\n`); }

function evaluateOnce(source) {
  return new Promise((resolve, reject) => {
    rejectEvaluation = (error) => { rejectEvaluation = null; reject(error); };
    server.eval(`${source}\n`, server.context, "js-console", (error, value) => {
      rejectEvaluation = null;
      if (error) reject(error);
      else resolve(value);
    });
  });
}

async function evaluate(source) {
  return evaluateOnce(source);
}

function isIdentifier(name) {
  if (!/^[$_\p{ID_Start}][$\u200c\u200d\p{ID_Continue}]*$/u.test(name)) return false;
  // Parsing a declaration rejects reserved words without modifying the context.
  try { new vm.Script(`let ${name};`); return true; } catch { return false; }
}

function exists(name) {
  if (name in server.context) return true;
  try { vm.runInContext(name, server.context); return true; }
  catch (error) {
    if (error?.name !== "ReferenceError") return true;
    // typeof on an absent name succeeds, but throws for a lexical TDZ binding.
    try { vm.runInContext(`typeof ${name}`, server.context); return false; }
    catch { return true; }
  }
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
    const get = () => fileNamespaces.get(path);
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

async function importModules(imports = []) {
  if (!Array.isArray(imports)) throw new Error("Expected an import list");
  const staged = new Map();
  // Validate all bindings before importing, so a collision cannot overwrite user state.
  const modules = imports.map(({ specifier, bindings: names }) => {
    if (typeof specifier !== "string" || !Array.isArray(names)) throw new Error("Invalid import declaration");
    const resolved = Bun.resolveSync(specifier, currentFile ? dirname(currentFile) : process.cwd());
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
  for (const { moduleId, names } of modules) {
    const namespace = await import(moduleId);
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

async function addSymbol(path, imported, requestedLocal) {
  if (typeof path !== "string" || typeof imported !== "string" ||
      (requestedLocal != null && !isIdentifier(requestedLocal))) throw new Error("Invalid symbol request");
  const moduleId = pathToFileURL(path).href;
  const namespace = await import(moduleId);
  if (!Object.hasOwn(namespace, imported)) throw new Error(`Module '${path}' has no export '${imported}'`);
  let local = requestedLocal ?? imported;
  if (!isIdentifier(local)) throw new Error(`Export '${imported}' needs a valid JavaScript alias`);
  const same = (name) => {
    const binding = bindings.get(name);
    return ownsBinding(name, binding) && binding.moduleId === moduleId && binding.imported === imported;
  };
  if (same(local) && bindings.get(local).origin === "import") return { local, already: true };
  if (exists(local) && !same(local)) {
    if (requestedLocal != null) throw new Error(`Cannot add '${local}': the name is already in use`);
    let suffix = 2;
    while (exists(`${local}_${suffix}`)) suffix++;
    local = `${local}_${suffix}`;
  }
  bind(local, () => namespace[imported], "import", moduleId, imported);
  return { local, already: false };
}

function fileDefaultAlias(path) {
  let stem = basename(path).replace(/\.[^.]+$/, "").replace(/[^A-Za-z0-9_$]/g, "_");
  if (!stem || /^[0-9]/.test(stem)) stem = `_${stem}`;
  return `${stem}_default`;
}

async function addFile(path, requestedBindings) {
  if (typeof path !== "string") throw new Error("Expected a file path");
  const moduleId = pathToFileURL(path).href;
  const previous = addedFiles.get(moduleId);
  if (previous) return { ...previous, already: true };
  if (requestedBindings != null && !Array.isArray(requestedBindings)) throw new Error("Invalid file bindings");
  const namespace = await import(moduleId);
  const requested = new Map();
  for (const entry of requestedBindings ?? []) {
    if (typeof entry?.exported !== "string" || !isIdentifier(entry.local) || requested.has(entry.exported)) {
      throw new Error("Invalid file binding");
    }
    requested.set(entry.exported, entry.local);
  }
  const staged = [];
  const taken = new Set();
  const unsupported = [];
  for (const exported of Object.keys(namespace)) {
    const base = exported === "default" ? fileDefaultAlias(path) : exported;
    if (!isIdentifier(base)) { unsupported.push(exported); continue; }
    let local = requested.get(exported) ?? base;
    const same = (name) => {
      const binding = bindings.get(name);
      return ownsBinding(name, binding) && binding.moduleId === moduleId && binding.imported === exported;
    };
    if (requested.has(exported) && (taken.has(local) || (exists(local) && !same(local)))) {
      local = base;
    }
    let suffix = 2;
    while (taken.has(local) || (exists(local) && !same(local))) local = `${base}_${suffix++}`;
    taken.add(local);
    staged.push({ exported, local, existing: same(local) });
  }
  for (const { exported, local, existing } of staged) {
    // A matching explicit import is already available. A matching auto-context
    // binding must become persistent before following another editor file.
    if (existing && bindings.get(local).origin !== "current") continue;
    bind(local, () => namespace[exported], "file", moduleId, exported);
  }
  const result = { bindings: staged.map(({ exported, local }) => ({ exported, local })), unsupported };
  addedFiles.set(moduleId, result);
  fileNamespaces.set(path, namespace);
  refreshQualifiedProperties();
  return { ...result, already: false };
}

async function reloadFile(path) {
  if (typeof path !== "string") throw new Error("Expected a file path");
  const moduleId = pathToFileURL(path).href;
  const isCurrent = currentFile === path;
  const previousFile = addedFiles.get(moduleId);
  if (!isCurrent && !previousFile) throw new Error("File is not in the console context");

  // Bun exposes imported ES modules through require.cache. Only this file is
  // invalidated; its imported dependencies retain their existing module state.
  delete require.cache[resolve(path)];
  // Immediately after WebStorm saves a document, Bun 1.4 can still serve the
  // previous transpiled module. Give its file cache a moment to observe the save.
  await Bun.sleep(100);
  const namespace = await import(moduleId);
  fileNamespaces.set(path, namespace);
  refreshQualifiedProperties();
  for (const [name, binding] of bindings) {
    if (binding.moduleId !== moduleId || !ownsBinding(name, binding)) continue;
    if (binding.imported === "*" || Object.hasOwn(namespace, binding.imported)) {
      binding.read = () => binding.imported === "*" ? namespace : namespace[binding.imported];
    } else if (binding.origin === "current" || binding.origin === "file") {
      delete server.context[name];
      bindings.delete(name);
    }
  }

  const names = [];
  const collisions = [];
  const unsupported = [];
  if (isCurrent) {
    for (const name of Object.keys(namespace)) {
      if (!isIdentifier(name)) { unsupported.push(name); continue; }
      if (exists(name)) {
        const binding = bindings.get(name);
        if (ownsBinding(name, binding) && binding.moduleId === moduleId && binding.imported === name) names.push(name);
        else collisions.push(name);
      } else {
        bind(name, () => namespace[name], "current", moduleId, name);
        names.push(name);
      }
    }
  }

  let fileResult = null;
  if (previousFile) {
    const requested = new Map(previousFile.bindings.map(({ exported, local }) => [exported, local]));
    const staged = [];
    const taken = new Set();
    const fileUnsupported = [];
    for (const exported of Object.keys(namespace)) {
      const base = exported === "default" ? fileDefaultAlias(path) : exported;
      if (!isIdentifier(base)) { fileUnsupported.push(exported); continue; }
      let local = requested.get(exported) ?? base;
      const same = (name) => {
        const binding = bindings.get(name);
        return ownsBinding(name, binding) && binding.moduleId === moduleId && binding.imported === exported;
      };
      let suffix = 2;
      while (taken.has(local) || (exists(local) && !same(local))) local = `${base}_${suffix++}`;
      taken.add(local);
      staged.push({ exported, local, existing: same(local) });
    }
    for (const { exported, local, existing } of staged) {
      if (existing && bindings.get(local).origin !== "current") continue;
      bind(local, () => namespace[exported], "file", moduleId, exported);
    }
    fileResult = { bindings: staged.map(({ exported, local }) => ({ exported, local })), unsupported: fileUnsupported };
    addedFiles.set(moduleId, fileResult);
  }
  return { names, collisions, unsupported, file: fileResult };
}

function removeFile(path) {
  if (typeof path !== "string") throw new Error("Expected a file path");
  const moduleId = pathToFileURL(path).href;
  const file = addedFiles.get(moduleId);
  if (!file) return { removed: false };
  for (const { exported, local } of file.bindings) {
    const binding = bindings.get(local);
    if (binding?.origin !== "file" || binding.moduleId !== moduleId || binding.imported !== exported || !ownsBinding(local, binding)) continue;
    delete server.context[local];
    bindings.delete(local);
  }
  addedFiles.delete(moduleId);
  if (currentFile !== path) fileNamespaces.delete(path);
  refreshQualifiedProperties();
  return { removed: true };
}

async function dispatch(request) {
  switch (request.op) {
    case "eval": {
      if (typeof request.code !== "string") throw new Error("Expected JavaScript source");
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
      const value = await evaluate(request.code);
      return { text: inspect(value, { colors: false, depth: 4, maxArrayLength: 100, maxStringLength: 10000, customInspect: false }) };
    }
    case "load": {
      // Import first: a failed import leaves the old context usable.
      const namespace = request.path ? await import(pathToFileURL(request.path).href) : {};
      clearAutomaticFileBindings();
      currentFile = request.path ?? null;
      fileNamespaces.clear();
      if (request.path) fileNamespaces.set(request.path, namespace);
      refreshQualifiedProperties();
      const names = [];
      const collisions = [];
      const unsupported = [];
      for (const name of Object.keys(namespace)) {
        if (!isIdentifier(name)) { unsupported.push(name); continue; }
        if (exists(name)) {
          const binding = bindings.get(name);
          if (ownsBinding(name, binding) && binding.moduleId === pathToFileURL(request.path).href && binding.imported === name) names.push(name);
          else collisions.push(name);
          continue;
        }
        bind(name, () => namespace[name], "current", pathToFileURL(request.path).href, name);
        names.push(name);
      }
      return { names, collisions, unsupported, path: request.path ?? null };
    }
    case "add_symbol": return addSymbol(request.path, request.imported, request.local);
    case "add_file": return addFile(request.path, request.bindings);
    case "reload_file": return reloadFile(request.path);
    case "remove_file": return removeFile(request.path);
    case "cached_files": return { paths: Object.keys(require.cache) };
    case "ping": return { version: process.versions.bun };
    default: throw new Error(`Unknown console operation: ${request.op}`);
  }
}

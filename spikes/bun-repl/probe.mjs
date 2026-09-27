// Feasibility experiment only. Run explicitly with Bun after user approval.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const workerFlag = "--repl-probe-worker";

if (process.argv.includes(workerFlag)) {
  await runWorker();
} else {
  await runParent();
}

async function runParent() {
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), workerFlag], {
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
    cwd: fileURLToPath(new URL("./", import.meta.url)),
  });
  let timedOut = false;
  const deadline = setTimeout(() => {
    timedOut = true;
    child.kill();
  }, 15_000);
  child.stdout.pipe(process.stdout);
  child.stderr.pipe(process.stderr);
  await new Promise((resolve) => {
    child.once("error", (error) => {
      clearTimeout(deadline);
      console.error(error.message);
      process.exitCode = 1;
      resolve();
    });
    child.once("close", (code, signal) => {
      clearTimeout(deadline);
      if (timedOut || code !== 0) {
        console.error(JSON.stringify({ event: "probe-failed", timedOut, code, signal }));
        process.exitCode = 1;
      }
      resolve();
    });
  });
}

async function runWorker() {
  let server;
  let input;
  let output;
  let failures = 0;
  const report = (event) => process.stdout.write(`${JSON.stringify(event)}\n`);
  report({ event: "runtime", bun: process.versions.bun ?? null, executable: process.execPath });

  try {
    assert.ok(process.versions.bun, "This experiment must run under Bun");
    const { default: repl } = await import("node:repl");
    const { PassThrough } = await import("node:stream");
    input = new PassThrough();
    output = new PassThrough();
    // Drain REPL display output; evaluator callbacks carry the tested values.
    output.resume();
    server = repl.start({ prompt: "", terminal: false, useGlobal: false, input, output });
    assert.ok(server.context);
    assert.equal(typeof server.eval, "function");
    report({ event: "check", name: "construct-stream-backed-repl", passed: true });

    const evaluate = (source) => new Promise((resolve, reject) => {
      const deadline = setTimeout(() => reject(new Error("Evaluator callback timed out")), 1500);
      try {
        server.eval(`${source}\n`, server.context, "js-console-probe", (error, value) => {
          clearTimeout(deadline);
          if (error) reject(error);
          else resolve(value);
        });
      } catch (error) {
        clearTimeout(deadline);
        reject(error);
      }
    });
    const check = async (name, work) => {
      try {
        await work();
        report({ event: "check", name, passed: true });
      } catch (error) {
        failures++;
        report({ event: "check", name, passed: false, error: String(error) });
      }
    };

    await check("persistent-lexical-binding", async () => {
      await evaluate("const consoleOwned = 21");
      assert.equal(await evaluate("consoleOwned * 2"), 42);
    });
    await check("ordinary-javascript", async () => {
      assert.equal(await evaluate("[1,2,3].reduce((a,b) => a+b, 0)"), 6);
      assert.equal(await evaluate('new Date("2026-09-23").toISOString()'), "2026-09-23T00:00:00.000Z");
      assert.equal(await evaluate('/foo-(\\d+)/.exec("foo-42")[1]'), "42");
    });
    await check("typescript-export-injection-and-removal", async () => {
      const namespace = await import(new URL("./fixtures/current.ts", import.meta.url).href);
      assert.equal("twice" in server.context, false);
      Object.defineProperty(server.context, "twice", {
        value: namespace.twice, configurable: true, writable: true, enumerable: true,
      });
      assert.equal(await evaluate("twice(21)"), 42);
      assert.equal(delete server.context.twice, true);
      assert.equal(await evaluate("typeof twice"), "undefined");
      assert.equal(await evaluate("consoleOwned"), 21);
    });
    await check("lexical-shadowing-observation", async () => {
      await evaluate("const lexicalCollision = 7");
      const visibleAsProperty = Object.hasOwn(server.context, "lexicalCollision");
      // Deliberately unsafe injection, ONLY to expose the hazard in this fixture.
      if (!visibleAsProperty) {
        Object.defineProperty(server.context, "lexicalCollision", {
          value: 99, configurable: true,
        });
        assert.equal(await evaluate("lexicalCollision"), 7);
        delete server.context.lexicalCollision;
      }
      report({ event: "observation", name: "lexical-binding-is-context-property", value: visibleAsProperty });
    });
    await check("top-level-await", async () => {
      assert.equal(await evaluate("await Promise.resolve(42)"), 42);
    });
  } catch (error) {
    failures++;
    report({ event: "setup-failed", error: String(error) });
  } finally {
    server?.close();
    input?.destroy();
    output?.destroy();
  }
  report({ event: "complete", failures });
  process.exitCode = failures ? 1 : 0;
}

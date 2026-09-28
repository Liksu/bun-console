// Checks 19–21: long commands, uncaught errors, a blocked JavaScript thread.

export function sleep(ms: number): Promise<string> {
  return new Promise((resolve) => setTimeout(() => resolve(`slept ${ms} ms`), ms));
}

export function failLater(ms = 500): Promise<never> {
  return new Promise((_, reject) => setTimeout(() => reject(new Error(`failed after ${ms} ms`)), ms));
}

export function throwFromTimer(): string {
  setTimeout(() => { throw new Error("thrown from a timer"); }, 100);
  return "timer scheduled";
}

export function busy(seconds: number): number {
  const end = Date.now() + seconds * 1000;
  let spins = 0;
  while (Date.now() < end) spins++;
  return spins;
}

export function spawnChild(): number {
  // A child process that never exits by itself; Restart Runtime must end it.
  return Bun.spawn([process.execPath, "-e", "setInterval(() => {}, 1000)"]).pid;
}

// Try:
//   await sleep(5000)        then immediately   1 + 1   -> 2 at once; header shows "running…"
//   failLater()              -> "Uncaught (in promise) Error: failed after 500 ms", runtime keeps variables
//   await failLater()        -> the error belongs to this command's number
//   throwFromTimer()         -> "Uncaught Error: thrown from a timer"
//   busy(3)                  -> "running…" for 3 s, then a number
//   while (true) {}          -> after ~2 s "JavaScript is busy" + Restart Runtime button
//   spawnChild()             -> a pid; after Restart Runtime that process is gone (Task Manager)

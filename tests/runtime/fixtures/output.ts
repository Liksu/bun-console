// Check 22: output and the result always appear in order.

export function report(items: string[]): number {
  console.log("start", { count: items.length });
  for (const item of items) console.info(" -", item);
  console.warn("warning goes to stderr (red)");
  console.table(items.map((name, index) => ({ index, name })));
  process.stdout.write("raw stdout write\n");
  return items.length;
}

export function makeList(): number[] {
  return [3, 1, 2];
}

// Try:
//   report(["a", "b", "c"])               -> all lines, then [N] 3
//   console.log(1); console.error(2); 3
//   makeList() instanceof Array           -> true (console and modules share one realm)
//   makeList().sort()

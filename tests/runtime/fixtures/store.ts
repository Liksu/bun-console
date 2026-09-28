// "Loaded by another module first": open uses-store.ts, run `useStore()`, THEN open this file.
// Its non-exported `items` stays unavailable (the console says so) until Restart Runtime,
// because another module had already loaded this file without the console's export list.
// Opening this file first (or after Restart) makes `items` available by name.

const items: string[] = [];

export function add(item: string): number {
  return items.push(item);
}

export function count(): number {
  return items.length;
}

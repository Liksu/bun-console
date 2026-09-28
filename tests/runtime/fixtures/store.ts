// Open uses-store.ts and run `useStore()`, THEN open this file:
// its non-exported `items` is available by name and shares that state: items -> [ 'apple', 'pear' ].
// (The console prepared this file's names when uses-store.ts, which imports it, became the context.)

const items: string[] = [];

export function add(item: string): number {
  return items.push(item);
}

export function count(): number {
  return items.length;
}

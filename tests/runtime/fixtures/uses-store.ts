import { add, count } from "./store";

export function useStore(): number {
  add("apple");
  add("pear");
  return count();
}

// Try:  useStore()   -> 2
// Then open store.ts: add, count and the non-exported items all work and share this state:
//   count() -> 2,   items -> [ 'apple', 'pear' ]

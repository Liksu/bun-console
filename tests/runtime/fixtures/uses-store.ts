import { add, count } from "./store";

export function useStore(): number {
  add("apple");
  add("pear");
  return count();
}

// Try:  useStore()   -> 2
// Then open store.ts: its exports (add, count) work and share this state: count() -> 2;
// the transcript explains why `items` is not available until Restart Runtime.

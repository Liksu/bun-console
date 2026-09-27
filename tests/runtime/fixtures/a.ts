export function twice(value: number) { return value * 2; }
export const a = "A";
export const process = "must not replace global process";
export const lexical = "must not replace console const";
export const undefinedUser = "must not replace undefined console const";
export let count = 0;
export function increment() { count++; }
export default 42;

const testHighlight = new Date().toISOString().split(/\D/).filter(Boolean).reduce((a, b) => a + b)

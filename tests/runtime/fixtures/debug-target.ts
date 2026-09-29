// Checks 16–17: the console's own debugger (Debugger toggle ON in the Bun Console title bar).
// Put a breakpoint on the `const total` line, then run in the console:
//   checkout([{ price: 10, qty: 2 }, { price: 5, qty: 1 }])
// While paused, type into the same console input:
//   items.length      subtotal      discount      subtotal * 2
// Then Continue (⋮ menu) -> the original command prints 22.5

type Item = { price: number; qty: number };

const TAX = 0.5;

export function checkout(items: Item[], discount = 0.1): number {
  const subtotal = items.reduce((sum, item) => sum + item.price * item.qty, 0);
  const total = subtotal * (1 - discount) * (1 + TAX) - 11.25;
  return Math.round(total * 100) / 100;
}

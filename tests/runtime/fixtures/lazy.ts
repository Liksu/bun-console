// Checks 17–18: switching to this tab must print nothing.
// The line below appears only when the console first uses a name from this file.
console.log("lazy.ts top-level code ran");

type Point = { x: number; y: number };

// Not exported: still callable from the console by name.
const origin: Point = { x: 0, y: 0 };
let moves = 0;

function distance(a: Point, b: Point = origin): number {
  moves++;
  return Math.hypot(a.x - b.x, a.y - b.y);
}

class Vector {
  constructor(public x: number, public y: number) {}
  length() { return distance(this); }
}

export function farthest(points: Point[]): Point {
  return points.reduce((best, p) => (distance(p) > distance(best) ? p : best));
}

export const unit = new Vector(1, 0);

// Try:
//   distance({ x: 3, y: 4 })          -> 5, and "lazy.ts top-level code ran" is printed once, before it
//   moves                             -> live value, grows with each call
//   new Vector(6, 8).length()         -> 10
//   farthest([{ x: 1, y: 1 }, { x: 5, y: 0 }])
//   unit instanceof Vector            -> true

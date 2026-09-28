// CommonJS file: names in module.exports work by name; other top-level names cannot be exposed.
// Try:
//   greet('you')   -> 'hello, you'
//   calls          -> ReferenceError explaining that calls is not in module.exports of legacy.cjs
//   globalThis['legacy.cjs'].default   -> the whole module.exports object

let calls = 0;

function greet(name) {
  calls++;
  return `hello, ${name}`;
}

module.exports = { greet };

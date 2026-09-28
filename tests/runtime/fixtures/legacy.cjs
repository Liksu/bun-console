// CommonJS file: only module.exports is available (as the default export).
// Pin it or use "Add File to JS Console": legacy_default.greet("you")
// As the editor file, it is reachable as globalThis['legacy.cjs'].default.greet("you")

function greet(name) {
  return `hello, ${name}`;
}

module.exports = { greet };

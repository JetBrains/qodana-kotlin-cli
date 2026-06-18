// JS inspection / ESLint planted defects for the qodana-js e2e.
function describe() {
  // JSUnusedLocalSymbols: `unused` is assigned but never read.
  const unused = 42;
  // JSEqualityComparisonWithCoercion: loose `==` instead of strict `===`.
  if (greet("qodana") == "HELLO QODANA") {
    return true;
  }
  return false;
}

function greet(name) {
  return ("hello " + name).toUpperCase();
}

module.exports = { describe, greet };

// Rust inspection planted defects for the qodana-rust e2e. No external crates: hermetic under
// `--network none`. The std import below is intentionally unused, and `unusedHelper` is dead code.

// RsUnusedImport: `std::fmt::Write` is imported but never used.
use std::fmt::Write;

// RsVariableNaming: a binding that is not snake_case.
fn compute() -> i32 {
    let MyValue = 42;
    MyValue
}

// Dead, never-called private function (an unused-declaration inspection should surface it).
fn unused_helper() -> &'static str {
    "never referenced"
}

fn main() {
    println!("{}", compute());
}

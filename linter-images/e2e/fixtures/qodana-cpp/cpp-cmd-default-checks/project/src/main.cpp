// Fixture for cpp-cmd-default-checks, the qodana-cpp (CLion) analog of qodana-clang's
// clang-cmd-default-checks. Plants one defect per engine CATEGORY in a well-formed TU (so no
// clang-diagnostic-error fires), so a silently disabled category reds the cell. Three are ASSERTED;
// the DFA one is planted but NOT asserted — CLion drops it in an async race on fast machines (QD-15334):
//   - Clang-Tidy  : bugprone-use-after-move — `moved` read after being std::move'd (ruleId ClangTidy). [asserted]
//   - native AST  : `taken` is never reassigned (ruleId CppLocalVariableMayBeConst).              [asserted]
//   - MISRA       : the non-zero octal literal (ruleId Misra; enabled via qodana.yaml).           [asserted]
//   - native DFA  : dead store — `dead`'s first value is never read (ruleId CppDFAUnusedValue).   [not asserted: QD-15334]
#include <string>
#include <utility>

static std::string consume(std::string s) {
    return s;
}

int compute(int input) {
    int dead = input * 2;         // CppDFAUnusedValue: value assigned here is never read
    dead = input + 1;             // overwritten before any read
    const int octal = 010;        // MISRA (C++ 2-13-2 / 5.13.2): non-zero octal constant. const so it
    return dead + octal;          // does not also trigger CppLocalVariableMayBeConst (kept to `taken`).
}

std::string run() {
    std::string moved = "qodana-cpp-fixture";
    std::string taken = consume(std::move(moved));  // CppLocalVariableMayBeConst: `taken` never reassigned
    return taken + moved;                           // bugprone-use-after-move: `moved` read after move.
}

int main() {
    return compute(static_cast<int>(run().size()));
}

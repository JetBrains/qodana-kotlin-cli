// Fixture for clang-cmd-default-checks (QD-15022).
// Plants two default-profile clang-tidy/clang-analyzer smells:
//   - bugprone-use-after-move: `moved` is read after being std::move'd.
//   - clang-analyzer / dead store: `dead` is assigned a value never read.
// Both must survive the default --checks=* profile (QD-14974: "analyzer
// found nothing"). The TU is well-formed so no clang-diagnostic-error fires.
#include <string>
#include <utility>

static std::string consume(std::string s) {
    return s;
}

int compute(int input) {
    int dead = input * 2;   // clang-analyzer-deadcode.DeadStores: never read
    dead = input + 1;       // overwritten before any read
    return dead;
}

std::string run() {
    std::string moved = "qodana-clang-fixture";
    std::string taken = consume(std::move(moved));
    // bugprone-use-after-move: `moved` read after being moved from.
    return taken + moved;
}

int main() {
    return compute(static_cast<int>(run().size()));
}

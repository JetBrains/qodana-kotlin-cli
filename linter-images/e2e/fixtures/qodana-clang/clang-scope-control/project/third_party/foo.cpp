// Fixture for clang-scope-control (QD-15022), NOT in compile_commands.json.
// Equally defective, but absent from the compile DB. QD-9251: files outside
// the DB must NOT be analyzed, so this TU must produce ZERO findings.
#include <string>
#include <utility>

static std::string consume(std::string s) {
    return s;
}

std::string run() {
    std::string moved = "scope-control-out-of-db";
    std::string taken = consume(std::move(moved));
    return taken + moved;  // bugprone-use-after-move — must stay UNREPORTED
}

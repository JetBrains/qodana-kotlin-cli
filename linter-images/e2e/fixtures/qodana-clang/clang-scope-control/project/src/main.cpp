// Fixture for clang-scope-control (QD-15022), IN compile_commands.json.
// Plants a bugprone-use-after-move so the scope assertion has a finding to
// anchor on (QD-10612: the finding must reference src/main.cpp).
#include <string>
#include <utility>

static std::string consume(std::string s) {
    return s;
}

std::string run() {
    std::string moved = "scope-control-in-db";
    std::string taken = consume(std::move(moved));
    return taken + moved;  // bugprone-use-after-move
}

int main() {
    return static_cast<int>(run().size());
}

package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the clang toolchain's trixie gcc-family repair (QD-15043). The shared `lib/toolchain/clang.dockerfile`
 * installs `clang-${CLANG}` from apt.llvm.org; that pulls `libobjc-${gcc}-dev`, which `=`-pins the stock
 * Debian gcc runtime (e.g. `gcc-14-base (= 14.2.0-19)`). On the dhi.io trixie debian-base those packages are
 * baked in at the vendor-patched `+dhi2` revision, and no `+dhi2` build of `libobjc-*-dev` exists, so apt
 * deadlocks with "held broken packages" and clang never installs (cpp, CLANG=20/trixie). The fix retries the
 * install behind an apt-preferences pin that forces the whole gcc runtime family back to the stock Debian
 * origin, downgrading it coherently so `libobjc-*-dev`'s `=` pins resolve.
 *
 * Bookworm (qodana-clang, CLANG=19) has no such skew — the LLVM bookworm repo's `libobjc-12-dev` matches the
 * base's stock gcc-12 — so the FIRST install succeeds and the pin branch never runs, keeping qodana-clang
 * byte-equivalent. The repair is therefore gated on the first attempt FAILING, mirroring lib/base's
 * `if ! apt-get check` repair idiom. EnvContractTest cannot see this (no `.env` key), so this reads the
 * fragment directly.
 */
class ClangToolchainTest {
    private val clang: String = Path.of("docker/lib/toolchain/clang.dockerfile").readText()

    @Test
    fun `clang toolchain repairs the gcc family only when the first install fails`() {
        // The repair must be gated on the first `clang-${CLANG}` install FAILING, so the healthy bookworm
        // path (clang-19) never writes the pin and stays byte-equivalent.
        assertTrue(
            Regex("""clang-\$\{?CLANG""").containsMatchIn(clang),
            "clang toolchain must install clang-\${CLANG} from the LLVM repo",
        )
        assertTrue(
            Regex("""\|\||if\s+!\s""").containsMatchIn(clang),
            "the gcc-family repair must be gated on the FIRST install failing (an `||` fallthrough or " +
                "`if !`), so bookworm (clang-19, no skew) succeeds first-try and never repairs",
        )
    }

    @Test
    fun `clang toolchain pins the gcc runtime family back to the stock Debian origin`() {
        // The repair writes an apt-preferences pin that raises the stock Debian origin (o=Debian) above the
        // vendor +dhi packages for the gcc runtime family, so apt downgrades the whole tree coherently and
        // libobjc-*-dev's `=` version pins resolve.
        assertTrue(
            Regex("""/etc/apt/preferences\.d/""").containsMatchIn(clang),
            "the repair must write an apt-preferences pin under /etc/apt/preferences.d/",
        )
        assertTrue(
            Regex("""Pin:\s*release\s+o=Debian""").containsMatchIn(clang),
            "the pin must target the stock Debian origin (Pin: release o=Debian) so the gcc runtime family " +
                "downgrades off the vendor +dhi revision to the version libobjc-*-dev requires",
        )
        assertTrue(
            Regex("""Pin-Priority:\s*1001""").containsMatchIn(clang),
            "the pin priority must be 1001 (> 1000) so apt performs the coherent DOWNGRADE off +dhi to stock",
        )
        // --allow-downgrades is required: the gcc runtime is already installed at the higher +dhi revision.
        assertTrue(
            Regex("""--allow-downgrades""").containsMatchIn(clang),
            "the retry must pass --allow-downgrades (the +dhi gcc runtime is already installed and the pin " +
                "downgrades it to stock)",
        )
    }

    @Test
    fun `clang toolchain removes the gcc pin so the shipped image keeps the vendor apt state`() {
        // The pin is a build-time repair only; it must be deleted before the layer ends so the final image's
        // apt state is not silently locked to stock Debian for everything gcc-related.
        assertTrue(
            Regex("""rm\s+-f\s+/etc/apt/preferences\.d/""").containsMatchIn(clang),
            "the build must `rm -f` the gcc pin file after the install so the shipped image does not carry a " +
                "lingering stock-Debian pin",
        )
    }
}

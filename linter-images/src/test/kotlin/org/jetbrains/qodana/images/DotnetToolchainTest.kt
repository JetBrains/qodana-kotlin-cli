package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the shared `.NET` toolchain fragment + the qodana-dotnet thin image (QD-15042).
 *
 * `lib/toolchain/dotnet.dockerfile` (`FROM base AS dotnet-toolchain`, the conda/clang install-stage
 * pattern) is SHARED by cdnet (Community, bookworm, dist-less) and dotnet (Ultimate Rider, trixie,
 * dist). cdnet was authored first and apt-installs `libicu72` (bookworm); the trixie base dotnet builds
 * on needs `libicu76`. The fragment is parameterized via an `.env`-keyed `LIBICU_PKG`: a BARE in-stage
 * `ARG LIBICU_PKG` (NO default — a default declared after dockerfile-x's INCLUDE_ARGS block would
 * CLOBBER an `.env` value back, the QODANA_UID trap) inherits the INCLUDE_ARGS global, and the apt line
 * uses `${LIBICU_PKG:-libicu72}` so cdnet (which sets nothing) still installs `libicu72` while
 * dotnet's `.env` `LIBICU_PKG=libicu76` flows through. The cdnet `.env` stays unchanged.
 *
 * dotnet is the FIRST dist+privileged+dotnet-toolchain image: its chain is
 * `base → node(in-place) → eslint(in-place) → dotnet-toolchain(FROM base, inherits node+eslint) →
 * privileged(FROM dotnet-toolchain) → dist(FROM privileged) → cli → runtime`. Rider analyzes JS/TS
 * (the upstream Rider base layers node + global eslint), so dotnet INCLUDEs node + eslint — but NOT
 * resharper-clt: Rider bundles InspectCode in its IDE dist, unlike cdnet which pulls the CLT mirror.
 */
class DotnetToolchainTest {
    private val lib: Path = Path.of("docker/lib")
    private val images: Path = Path.of("docker/images")

    private val dotnetFragment: String by lazy { lib.resolve("toolchain/dotnet.dockerfile").readText() }

    @Test
    fun `dotnet toolchain is an install stage off base`() {
        assertTrue(
            Regex("""(?m)^FROM base AS dotnet-toolchain$""").containsMatchIn(dotnetFragment),
            "dotnet toolchain must open a `FROM base AS dotnet-toolchain` install stage (so it inherits the " +
                "in-place node+eslint layered onto base before it)",
        )
    }

    @Test
    fun `dotnet toolchain declares a bare in-stage LIBICU_PKG arg (no default) to dodge the INCLUDE_ARGS clobber`() {
        // A `ARG LIBICU_PKG=libicu72` anywhere (pre-FROM or in-stage) would be emitted AFTER dockerfile-x's
        // INCLUDE_ARGS block and CLOBBER dotnet's `.env` LIBICU_PKG=libicu76 back to libicu72 (the
        // QODANA_UID trap). The ARG must be BARE so it inherits the INCLUDE_ARGS global value.
        assertTrue(
            Regex("""(?m)^\s*ARG LIBICU_PKG\s*$""").containsMatchIn(dotnetFragment),
            "dotnet toolchain must declare a BARE `ARG LIBICU_PKG` (no default) so the INCLUDE_ARGS value survives",
        )
        assertTrue(
            !Regex("""ARG LIBICU_PKG\s*=""").containsMatchIn(dotnetFragment),
            "dotnet toolchain must NOT give LIBICU_PKG an ARG default (it would clobber dotnet's .env libicu76)",
        )
    }

    @Test
    fun `dotnet toolchain installs the libicu package via the parameterized default so cdnet stays libicu72`() {
        // cdnet sets no LIBICU_PKG → the use-site `${LIBICU_PKG:-libicu72}` fallback keeps cdnet on
        // libicu72 (its effective install, and its .env, are unchanged). dotnet sets libicu76 in its .env.
        assertTrue(
            Regex("""\$\{LIBICU_PKG:-libicu72}""").containsMatchIn(dotnetFragment),
            "dotnet toolchain must apt-install `\${LIBICU_PKG:-libicu72}` so cdnet (no key) stays on libicu72",
        )
        // On the INSTRUCTION lines (comments stripped), the only `libicu72` must be the `:-` fallback —
        // a bare `libicu72` apt token would pin cdnet's package independently of the parameter. Comments
        // may freely mention libicu72 (the cdnet/bookworm note), so they are excluded.
        val instructions =
            dotnetFragment
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString("\n")
        assertTrue(
            !Regex("""(?<!:-)\blibicu72\b""").containsMatchIn(instructions),
            "the only `libicu72` on a dotnet-toolchain instruction line must be the " +
                "`\${LIBICU_PKG:-libicu72}` fallback",
        )
    }

    @Test
    fun `dotnet toolchain keeps the pinned dotnet-install script and the 8-9-10 SDK channels unchanged`() {
        // The SDK install (pinned dotnet-install.sh revision+sha, fail-closed sha256sum -c, channels
        // 8.0 9.0 10.0) is shared with cdnet and stays byte-equivalent — only libicu is parameterized.
        assertTrue(
            Regex("""ARG DOTNET_INSTALL_SH_REVISION=2e497bbe880cf47b209fe0d1f9c5e051916f830e""")
                .containsMatchIn(dotnetFragment),
            "dotnet toolchain must keep the pinned dotnet-install.sh revision",
        )
        assertTrue(
            Regex("""ARG DOTNET_INSTALL_SH_SHA256=3f30fbfa69e182be7e60fd0cd9189c53cb61799b6077159fec74341112f1715e""")
                .containsMatchIn(dotnetFragment),
            "dotnet toolchain must keep the pinned dotnet-install.sh sha256",
        )
        assertTrue(
            Regex("""ARG DOTNET_CHANNELS="8\.0 9\.0 10\.0"""").containsMatchIn(dotnetFragment),
            "dotnet toolchain must keep the 8.0 9.0 10.0 SDK channels",
        )
        assertTrue(
            Regex("""sha256sum -c""").containsMatchIn(dotnetFragment),
            "dotnet toolchain must keep the fail-closed `sha256sum -c` install-script verification",
        )
    }

    @Test
    fun `qodana-dotnet thin image composes node, eslint, dotnet, privileged, dist, cli, runtime in order`() {
        val thin = images.resolve("qodana-dotnet.dockerfile").readText()
        val expectedOrder =
            listOf(
                "INCLUDE lib/base.dockerfile",
                "INCLUDE lib/toolchain/node.dockerfile",
                "INCLUDE lib/toolchain/eslint.dockerfile",
                "INCLUDE lib/toolchain/dotnet.dockerfile",
                "INCLUDE lib/privileged.dockerfile",
                "INCLUDE lib/dist.dockerfile",
                "INCLUDE lib/cli.dockerfile",
                "INCLUDE lib/runtime.dockerfile",
            )
        val includes =
            thin
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("INCLUDE ") }
                .toList()
        assertTrue(
            includes == expectedOrder,
            "qodana-dotnet.dockerfile INCLUDEs must be exactly node→eslint→dotnet→privileged→dist→cli→runtime " +
                "(after base), was: $includes",
        )
        // Rider bundles InspectCode in its dist, so dotnet must NOT pull the cdnet CLT mirror.
        assertTrue(
            !thin.contains("resharper-clt"),
            "qodana-dotnet must NOT INCLUDE lib/resharper-clt.dockerfile (InspectCode ships in the Rider dist)",
        )
    }
}

package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the Rust toolchain stage (QD-15041). Unlike the in-place go/ruby ENV fragments, rust is an
 * install-STAGE (`FROM base AS rust-toolchain`, the conda/clang pattern): the base ships NO Rust, so
 * the fragment fetches a sha-pinned `rustup-init`, installs the pinned toolchain + `rust-src`, and the
 * dist layers onto this stage via DIST_BASE_STAGE=rust-toolchain. The cargo/rustc/rustup PROXIES live
 * at the STABLE CARGO_HOME=/usr/local/cargo/bin — NOT under /data/cache, which the runtime bind-mounts
 * an empty host dir over (that would shadow the executables off PATH at scan time; conda keeps its bin
 * at /opt/miniconda3 and go keeps go at /usr/local/go for the same reason). /usr/local/cargo is chowned
 * to uid 1000 so scan-time `cargo fetch` populates its registry as the unprivileged user.
 * RUSTUP_HOME=/usr/local/rustup holds the toolchains (world-readable). The rustup-init download is
 * fail-closed sha256-verified (the conda/android installer-pin convention). EnvContractTest covers the
 * `.env` key set; this reads the fragment + the thin image directly.
 */
class RustToolchainTest {
    private val lib: Path = Path.of("docker/lib")
    private val images: Path = Path.of("docker/images")

    @Test
    fun `rust toolchain is an install stage off base`() {
        val rust = lib.resolve("toolchain/rust.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^FROM base AS rust-toolchain$""").containsMatchIn(rust),
            "rust toolchain must open a `FROM base AS rust-toolchain` install stage (the conda/clang pattern)",
        )
    }

    @Test
    fun `rust toolchain keeps cargo on a stable path the runtime cache mount cannot shadow`() {
        val rust = lib.resolve("toolchain/rust.dockerfile").readText()
        assertTrue(
            Regex("""CARGO_HOME=/usr/local/cargo""").containsMatchIn(rust),
            "rust toolchain must set CARGO_HOME=/usr/local/cargo: the cargo/rustc PROXIES live at " +
                "\$CARGO_HOME/bin, and the runtime bind-mounts an empty host dir over /data/cache, so a " +
                "/data/cache CARGO_HOME would hide the executables off PATH at scan time (conda/go invariant)",
        )
        assertTrue(
            Regex("""RUSTUP_HOME=/usr/local/rustup""").containsMatchIn(rust),
            "rust toolchain must set RUSTUP_HOME=/usr/local/rustup (the toolchains live there)",
        )
        // The proxy/toolchain dirs must NOT sit under /data/cache (the runtime mount would shadow them).
        assertTrue(
            !Regex("""CARGO_HOME=/data/cache""").containsMatchIn(rust) &&
                !Regex("""RUSTUP_HOME=/data/cache""").containsMatchIn(rust),
            "rust must NOT place CARGO_HOME/RUSTUP_HOME under /data/cache (the runtime cache mount shadows it)",
        )
        // PATH must carry the cargo proxy dir + the rustup bin dir, both on the stable /usr/local path.
        assertTrue(
            Regex("""PATH=[^\n]*/usr/local/cargo/bin""").containsMatchIn(rust),
            "rust toolchain PATH must include /usr/local/cargo/bin (the cargo/rustc proxies)",
        )
        assertTrue(
            Regex("""PATH=[^\n]*/usr/local/rustup/bin""").containsMatchIn(rust),
            "rust toolchain PATH must include /usr/local/rustup/bin",
        )
    }

    @Test
    fun `rust toolchain makes the cargo home writable for scan-time cargo fetch as uid 1000`() {
        val rust = lib.resolve("toolchain/rust.dockerfile").readText()
        // /usr/local/cargo is root-owned by default; the scan runs as uid 1000 and `cargo fetch` writes
        // the registry under $CARGO_HOME/registry, so the dir must be chowned to uid 1000.
        assertTrue(
            Regex("""chown -R 1000:1000 [^\n]*/usr/local/cargo""").containsMatchIn(rust),
            "rust toolchain must chown /usr/local/cargo to uid 1000 so scan-time `cargo fetch` can write its registry",
        )
    }

    @Test
    fun `rust toolchain sha-verifies the rustup-init installer before exec`() {
        val rust = lib.resolve("toolchain/rust.dockerfile").readText()
        assertTrue(
            Regex("""ARG RUSTUP_INIT_SHA256""").containsMatchIn(rust),
            "rust toolchain must declare ARG RUSTUP_INIT_SHA256 (the .env-pinned installer sha)",
        )
        // Fail-closed: `sha256sum -c -` (or an `echo "<sha>  <file>" | sha256sum -c`) must run BEFORE
        // the rustup-init is executed, mirroring conda/android's installer sha-pin.
        assertTrue(
            Regex("""sha256sum -c""").containsMatchIn(rust),
            "rust toolchain must `sha256sum -c` the rustup-init before exec (the installer-pin convention)",
        )
        assertTrue(
            Regex("""\$\{?RUSTUP_INIT_SHA256""").containsMatchIn(rust),
            "rust toolchain must interpolate RUSTUP_INIT_SHA256 into the checksum line",
        )
    }

    @Test
    fun `rust toolchain installs the pinned toolchain plus rust-src and keeps build-essential and pkg-config`() {
        val rust = lib.resolve("toolchain/rust.dockerfile").readText()
        assertTrue(
            Regex("""--default-toolchain "?\$\{?RUST_VERSION""").containsMatchIn(rust),
            "rust toolchain must install the RUST_VERSION-pinned default toolchain via rustup-init",
        )
        assertTrue(
            Regex("""rustup component add rust-src""").containsMatchIn(rust),
            "rust toolchain must `rustup component add rust-src` (the source rust.Dockerfile does)",
        )
        // build-essential + pkg-config are real runtime deps for native crates; they must NOT be purged.
        assertTrue(
            Regex("""build-essential""").containsMatchIn(rust),
            "rust toolchain must install build-essential (native crates need a C toolchain at scan time)",
        )
        assertTrue(
            Regex("""pkg-config""").containsMatchIn(rust),
            "rust toolchain must install pkg-config (native crates need it at scan time)",
        )
    }

    @Test
    fun `qodana-rust thin image includes the rust toolchain fragment`() {
        val thin = images.resolve("qodana-rust.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^INCLUDE lib/toolchain/rust\.dockerfile$""").containsMatchIn(thin),
            "qodana-rust.dockerfile must INCLUDE lib/toolchain/rust.dockerfile (the install stage)",
        )
    }
}

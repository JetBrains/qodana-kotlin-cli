package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The shared lib/ plumbing derives CPU arch from BuildKit `TARGETARCH`, not from `.env` arch keys, so
 * the same Dockerfiles build amd64 (TARGETARCH=amd64) and arm64 (TARGETARCH=arm64). Test CWD is the
 * module root; lib/ files are read relative to it (cf. ComposeContractTest).
 */
class MultiarchPlumbingContractTest {
    private fun lib(name: String): String = Path.of("docker/lib/$name").readText()

    // Upstream-canonical tini v0.19.0 digests (fetched from the krallin/tini release, not invented):
    // the test owns the source of truth, so a typo OR a swap in the dockerfile is caught locally
    // (BuildKit's ADD --checksum is the authoritative check in CI; this gives earlier, host-free signal).
    private val tiniArchToSha =
        mapOf(
            "amd64" to "93dcc18adc78c65a028a84799ecf8ad40c936fdfc5f2a57b1acda5a8117fa82c",
            "arm64" to "07952557df20bfd2a95f9bef198b445e006171969499a1d361bd9e6f8e5e0e81",
        )

    @Test
    fun `cli dockerfile passes TARGETARCH to install-cli and drops CLI_ARCH`() {
        val d = lib("cli.dockerfile")
        assertTrue(
            Regex("""--arch\s+"\$\{TARGETARCH\}"""").containsMatchIn(d),
            "cli.dockerfile must pass --arch \"\${TARGETARCH}\" to install-cli",
        )
        assertFalse(d.contains("CLI_ARCH"), "cli.dockerfile must not reference CLI_ARCH")
        assertTrue(d.contains("ARG TARGETARCH"), "cli-builder must declare ARG TARGETARCH")
    }

    @Test
    fun `dist dockerfile passes TARGETARCH to provision-dist`() {
        val d = lib("dist.dockerfile")
        assertTrue(
            Regex("""--arch\s+"\$\{TARGETARCH\}"""").containsMatchIn(d),
            "dist.dockerfile must pass --arch \"\${TARGETARCH}\" to provision-dist",
        )
        assertTrue(d.contains("ARG TARGETARCH"), "dist-builder must declare ARG TARGETARCH")
    }

    @Test
    fun `runtime dockerfile selects tini per-TARGETARCH and verifies its pinned sha fail-closed`() {
        // `COPY --from=tini-${TARGETARCH}` is NOT viable (dockerfile-x doesn't expand the stage variable),
        // so tini is fetched in a RUN that selects tini-$TARGETARCH + its sha by `case "$TARGETARCH"`.
        val d = lib("runtime.dockerfile")
        // Exact upstream digests pinned as ARG defaults (catches a typo; a RUN curl has no BuildKit
        // ADD --checksum pre-verify, so this is the earliest, host-free guard on the pin's correctness).
        assertTrue(d.contains("ARG TINI_SHA256_AMD64=${tiniArchToSha["amd64"]}"), "amd64 tini sha pinned to upstream")
        assertTrue(d.contains("ARG TINI_SHA256_ARM64=${tiniArchToSha["arm64"]}"), "arm64 tini sha pinned to upstream")
        // Selection correctness: each arch case-arm maps to ITS sha var (catches a swap).
        assertTrue(d.contains("amd64) tini_sha=\"\${TINI_SHA256_AMD64}\""), "amd64 case must select TINI_SHA256_AMD64")
        assertTrue(d.contains("arm64) tini_sha=\"\${TINI_SHA256_ARM64}\""), "arm64 case must select TINI_SHA256_ARM64")
        // Fetch the arch-matching binary and verify fail-closed.
        assertTrue(d.contains("tini-\${TARGETARCH}"), "must fetch tini-\${TARGETARCH}")
        assertTrue(d.contains("curl -fsSL"), "tini fetch must use curl -fsSL (fail on http error)")
        assertTrue(d.contains("sha256sum -c"), "tini must be verified fail-closed via sha256sum -c")
        assertFalse(d.contains("\${TINI_ARCH}"), "runtime.dockerfile must not reference TINI_ARCH")
    }

    @Test
    fun `conda toolchain selects the Miniconda installer per-TARGETARCH fail-closed`() {
        val d = Path.of("docker/lib/toolchain/conda.dockerfile").readText()
        // ARG TARGETARCH must be re-declared IN the stage (a pre-FROM-only decl expands empty → the `*)` arm
        // aborts BOTH arches); the cli/dist tests guard this for the same reason.
        assertTrue(d.contains("ARG TARGETARCH"), "conda-toolchain stage must re-declare ARG TARGETARCH")
        // Each arm maps to ITS sha var (swap-proof), the URL selects via the resolved ${mc_arch}, and the
        // installer is sha256sum -c verified with a fail-closed `*)` default — the runtime.dockerfile tini shape.
        assertTrue(d.contains("amd64) mc_arch=x86_64; mc_sha=\"\${MINICONDA_SHA256_X86_64}\""), "amd64 sha arm")
        assertTrue(d.contains("arm64) mc_arch=aarch64; mc_sha=\"\${MINICONDA_SHA256_AARCH64}\""), "arm64 sha arm")
        assertTrue(d.contains("Miniconda3-\${MINICONDA_VERSION}-Linux-\${mc_arch}.sh"), "URL must use mc_arch")
        assertTrue(Regex("""\*\)\s*echo "unsupported TARGETARCH""").containsMatchIn(d), "fail-closed default case arm")
        assertTrue(d.contains("sha256sum -c"), "Miniconda must be verified fail-closed")
        assertFalse(d.contains("Linux-x86_64.sh"), "no hardcoded x86_64 installer URL")
    }

    @Test
    fun `rust toolchain selects rustup-init per-TARGETARCH fail-closed`() {
        val d = Path.of("docker/lib/toolchain/rust.dockerfile").readText()
        // In-stage ARG TARGETARCH is load-bearing (rust.dockerfile documents that a pre-FROM-only decl
        // expands empty → the `*)` arm aborts both arches); mirror the cli/dist guards.
        assertTrue(d.contains("ARG TARGETARCH"), "rust-toolchain stage must re-declare ARG TARGETARCH")
        assertTrue(
            d.contains("amd64) rust_arch=x86_64-unknown-linux-gnu; rustup_sha=\"\${RUSTUP_INIT_SHA256_X86_64}\""),
            "amd64 arm",
        )
        assertTrue(
            d.contains("arm64) rust_arch=aarch64-unknown-linux-gnu; rustup_sha=\"\${RUSTUP_INIT_SHA256_AARCH64}\""),
            "arm64 arm",
        )
        assertTrue(d.contains("dist/\${rust_arch}/rustup-init"), "installer URL must use \${rust_arch}")
        assertTrue(d.contains("curl -fsSL"), "rustup-init fetch must use curl -fsSL (fail on http error)")
        assertTrue(Regex("""\*\)\s*echo "unsupported TARGETARCH""").containsMatchIn(d), "fail-closed default arm")
        assertTrue(d.contains("sha256sum -c"), "fail-closed verify")
        assertFalse(
            d.contains("x86_64-unknown-linux-gnu/rustup-init"),
            "no hardcoded x86_64 installer URL (must use \${rust_arch})",
        )
    }
}

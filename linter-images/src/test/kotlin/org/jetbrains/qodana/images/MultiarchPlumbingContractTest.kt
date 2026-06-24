package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `runtime dockerfile fetches tini per-arch via declarative checksum stages selected by TARGETARCH`() {
        val d = lib("runtime.dockerfile")
        // The per-arch tini stage set must EQUAL the supported arch set (no missing arch, no rogue extra).
        // Parse each `FROM scratch AS tini-<arch>` chunk and capture its ADD --checksum digest — asserting
        // the exact arch->digest PAIRING (catches a swapped or mistyped digest), not mere presence.
        val pairing =
            d
                .split(Regex("""(?=^FROM )""", RegexOption.MULTILINE))
                .mapNotNull { chunk ->
                    val arch = Regex("""^FROM scratch AS tini-(\w+)""").find(chunk.trim())?.groupValues?.get(1)
                    val sha = Regex("""ADD --checksum=sha256:([0-9a-f]{64})""").find(chunk)?.groupValues?.get(1)
                    if (arch != null) arch to sha else null
                }.toMap()
        assertEquals(tiniArchToSha, pairing, "each tini-<arch> stage must pin exactly its upstream digest")
        assertTrue(d.contains("COPY --from=tini-\${TARGETARCH}"), "runtime must COPY --from=tini-\${TARGETARCH}")
        assertFalse(d.contains("\${TINI_ARCH}"), "runtime.dockerfile must not reference TINI_ARCH")
    }
}

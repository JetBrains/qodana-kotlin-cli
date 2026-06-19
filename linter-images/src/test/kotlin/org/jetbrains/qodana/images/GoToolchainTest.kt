package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the Go module-cache redirect (QD-15037). The dhi.io/golang base defaults `GOMODCACHE` to
 * `/go/pkg/mod`, which is ROOT-owned; the qodana-go image runs scans as the unprivileged uid 1000, so a
 * Go project that resolves external modules would fail to populate that cache. The source qodana-cli
 * `go.Dockerfile` redirects `GOMODCACHE` to the writable `/data/cache` mount (`$QODANA_DATA/cache/go`);
 * `lib/toolchain/go.dockerfile` does the same, in-place on `base`. `lib/base.dockerfile` already creates
 * `/data/cache` owned by the qodana user, so the redirected cache is writable at scan time. EnvContractTest
 * cannot see this (no `.env` key), so this reads the fragment + the go thin image directly.
 */
class GoToolchainTest {
    private val lib: Path = Path.of("docker/lib")
    private val images: Path = Path.of("docker/images")

    @Test
    fun `go toolchain fragment redirects GOMODCACHE to the writable data cache`() {
        val go = lib.resolve("toolchain/go.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^ENV GOMODCACHE=/data/cache/go$""").containsMatchIn(go),
            "go toolchain must set ENV GOMODCACHE=/data/cache/go (the writable mount; the base default " +
                "/go/pkg/mod is root-owned and breaks scans of module projects as uid 1000)",
        )
    }

    @Test
    fun `qodana-go thin image includes the go toolchain fragment`() {
        val thin = images.resolve("qodana-go.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^INCLUDE lib/toolchain/go\.dockerfile$""").containsMatchIn(thin),
            "qodana-go.dockerfile must INCLUDE lib/toolchain/go.dockerfile (the GOMODCACHE redirect)",
        )
    }
}

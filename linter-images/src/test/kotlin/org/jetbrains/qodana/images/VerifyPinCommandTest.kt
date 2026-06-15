package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import org.jetbrains.qodana.images.dist.DistVerifier
import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * verify-pin re-resolves the pinned feed Link + .sha256 + .sha256.asc over the canonical
 * FeedClient/DistVerifier and RE-VERIFIES GPG+sha256 (the canary's compiled, tested re-verification).
 * Green when the upstream signature matches; fails closed (throws — a non-zero process exit) when the
 * pinned link 404s.
 */
class VerifyPinCommandTest {
    private val fingerprint = "B46DC71E03FEEB7F89D1F2491F7A8F87B9D8F501"
    private val goodSha = "a".repeat(64)
    private val build = "253.1234.56"
    private val archiveName = "qodana-QDJVM-$build.tar.gz"
    private val link = "https://download.jetbrains.com/qodana/2025.3/$archiveName"
    private val feedBody =
        """
        {"Code":"QDJVM","Releases":[
          {"Date":"2025-09-15","Type":"release","Version":"2025.3.1","MajorVersion":"2025.3","Build":"$build",
           "Downloads":{"linux":{"Link":"$link","ChecksumLink":"$link.sha256","Size":1}}}
        ]}
        """.trimIndent()

    @Test
    fun `re-verifies the pinned dist green when the upstream signature matches`(
        @TempDir tmp: Path,
    ) {
        val runner =
            FakeCommandRunner().apply {
                on({ it.contains("curl") }) { argv ->
                    val out = Path.of(argv[argv.indexOf("-o") + 1])
                    val url = argv.last()
                    val body =
                        when {
                            url.endsWith(".releases.json") -> feedBody
                            url.endsWith(".sha256.asc") -> "-----BEGIN PGP SIGNATURE-----"
                            url.endsWith(".sha256") -> "$goodSha  $archiveName\n"
                            else -> "archive-bytes"
                        }
                    Files.createDirectories(out.parent)
                    Files.writeString(out, body)
                    CommandResult(0, "", "")
                }
                on({ it.contains("--import") }, CommandResult(0, "", ""))
                on({ it.contains("--verify") }, CommandResult(0, "[GNUPG:] VALIDSIG $fingerprint 2025-09-15\n", ""))
                on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha  $archiveName\n", ""))
            }
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val cmd = VerifyPinCommand(FeedClient(runner), DistVerifier(runner)) { null }
        val result =
            cmd.test(
                "--linter-slug qodana-jvm --version 2025.3 --build $build " +
                    "--gpg-key $key --gpg-fingerprint $fingerprint",
            )
        assertEquals(0, result.statusCode, result.output)
    }

    @Test
    fun `fails closed when the pinned feed link 404s`(
        @TempDir tmp: Path,
    ) {
        // curl returns 22 (HTTP error) → FeedClient.fetch throws → verify-pin fails closed (a non-zero
        // process exit). The throw IS the fail-closed signal; assert it propagates rather than being
        // swallowed into a silent success.
        val runner =
            FakeCommandRunner().apply {
                on({ it.contains("curl") }, CommandResult(22, "", "curl: (22) HTTP 404"))
            }
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val cmd = VerifyPinCommand(FeedClient(runner), DistVerifier(runner)) { null }
        assertThrows<IllegalArgumentException> {
            cmd.test(
                "--linter-slug qodana-jvm --version 2025.3 --build $build " +
                    "--gpg-key $key --gpg-fingerprint $fingerprint",
            )
        }
    }

    @Test
    fun `fails closed when channel is private but the token is unset`(
        @TempDir tmp: Path,
    ) {
        // A private canary with no QD_FEED_TOKEN must error up front, not fetch anonymously and surface a
        // misleading "feed fetch failed". The token check precedes any network call.
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val cmd = VerifyPinCommand(FeedClient(FakeCommandRunner()), DistVerifier(FakeCommandRunner())) { null }
        assertThrows<IllegalStateException> {
            cmd.test(
                "--linter-slug qodana-jvm --version 2025.3 --build $build --channel private " +
                    "--gpg-key $key --gpg-fingerprint $fingerprint",
            )
        }
    }
}

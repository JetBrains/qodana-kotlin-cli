package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import org.jetbrains.qodana.images.dist.DistVerifier
import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    // Canonical FakeCommandRunner: curl writes the file it is told to (-o <path>), gpg signer-matches,
    // sha256sum echoes the good digest.
    private fun runner(): FakeCommandRunner =
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

    private fun baseArgs(
        feedUrl: String,
        key: Path,
    ) = "--distribution-feed $feedUrl --linter-slug qodana-jvm --version 2025.3 --build $build " +
        "--gpg-key $key --gpg-fingerprint $fingerprint"

    @Test
    fun `re-verifies the pinned dist green when the upstream signature matches`(
        @TempDir tmp: Path,
    ) {
        val runner = runner()
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val cmd = VerifyPinCommand(FeedClient(runner), DistVerifier(runner)) { null }
        val result = cmd.test(baseArgs("https://download.jetbrains.com/qodana/feed", key))
        assertEquals(0, result.statusCode, result.output)
    }

    @Test
    fun `distribution-feed URL is forwarded verbatim to FeedClient`(
        @TempDir tmp: Path,
    ) {
        val customFeed = "https://custom.example.com/feed"
        val runner = runner()
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val cmd = VerifyPinCommand(FeedClient(runner), DistVerifier(runner)) { null }

        val result = cmd.test(baseArgs(customFeed, key))

        assertEquals(0, result.statusCode, result.output)
        val feedCurl = runner.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        val feedUrl = feedCurl.last { it.endsWith(".releases.json") }
        assertTrue(feedUrl.startsWith(customFeed), "expected feed URL to start with '$customFeed', got: $feedUrl")
    }

    @Test
    fun `token from getEnv is forwarded to FeedClient when set, null when unset`(
        @TempDir tmp: Path,
    ) {
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val feedUrl = "https://download.jetbrains.com/qodana/feed"

        // Token present: the feed curl carries the bearer header.
        val runnerWithToken = runner()
        val cmdWithToken =
            VerifyPinCommand(FeedClient(runnerWithToken), DistVerifier(runnerWithToken)) { name ->
                if (name == "QODANA_READ_SPACE_PACKAGES_TOKEN") "tok-123" else null
            }
        val resultWithToken = cmdWithToken.test(baseArgs(feedUrl, key))
        assertEquals(0, resultWithToken.statusCode, resultWithToken.output)
        val feedCurlWithToken =
            runnerWithToken.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        assertTrue(
            feedCurlWithToken.any { it.contains("tok-123") },
            "expected bearer token in curl args: $feedCurlWithToken",
        )

        // Token absent: the feed curl has no Authorization header.
        val runnerNoToken = runner()
        val cmdNoToken = VerifyPinCommand(FeedClient(runnerNoToken), DistVerifier(runnerNoToken)) { null }
        val resultNoToken = cmdNoToken.test(baseArgs(feedUrl, key))
        assertEquals(0, resultNoToken.statusCode, resultNoToken.output)
        val feedCurlNoToken =
            runnerNoToken.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        assertTrue(
            feedCurlNoToken.none { it.contains("Authorization") },
            "expected no Authorization in curl args: $feedCurlNoToken",
        )
    }

    @Test
    fun `FeedClient failure propagates loudly`(
        @TempDir tmp: Path,
    ) {
        // curl returns a non-zero exit (a 401/404/network error) → FeedClient.fetch throws → verify-pin
        // fails closed (a non-zero process exit). The throw IS the fail-closed signal; assert it
        // propagates rather than being swallowed into a silent success.
        val failingRunner =
            FakeCommandRunner().apply {
                on({ it.contains("curl") }, CommandResult(22, "", "curl: (22) HTTP 404"))
            }
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val cmd = VerifyPinCommand(FeedClient(failingRunner), DistVerifier(failingRunner)) { null }
        // Clikt's test() only catches CliktError, so any other exception is unhandled — fail-closed.
        assertFailsWith<Exception> {
            cmd.test(baseArgs("https://packages.jetbrains.team/files/p/qd/private-feed", key))
        }
    }

    private fun sha256Args(feedUrl: String) =
        "--distribution-feed $feedUrl --linter-slug qodana-jvm --version 2025.3 --build $build --verify-mode sha256"

    @Test
    fun `sha256 mode re-verifies with a token, no gpg, never curls the asc`() {
        val runner = runner()
        val cmd =
            VerifyPinCommand(FeedClient(runner), DistVerifier(runner)) {
                if (it == "QODANA_READ_SPACE_PACKAGES_TOKEN") "read-tok" else null
            }
        val result =
            cmd.test(sha256Args("https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed"))
        assertEquals(0, result.statusCode, result.output)
        assertTrue(
            runner.invocations.none { c -> c.contains("curl") && c.last().endsWith(".asc") },
            "sha256 canary must not curl the .asc",
        )
        assertTrue(runner.invocations.none { it.firstOrNull() == "gpg" }, "sha256 canary must not run gpg")
    }

    @Test
    fun `sha256 mode hard-fails without a token`() {
        val cmd = VerifyPinCommand(FeedClient(runner()), DistVerifier(runner())) { null }
        val result =
            cmd.test(sha256Args("https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed"))
        assertTrue(result.statusCode != 0, "missing token must hard-fail: ${result.output}")
        assertTrue(result.output.contains("QODANA_READ_SPACE_PACKAGES_TOKEN"), result.output)
    }

    @Test
    fun `gpg mode without a key fails`() {
        val cmd = VerifyPinCommand(FeedClient(runner()), DistVerifier(runner())) { null }
        val result =
            cmd.test(
                "--distribution-feed https://download.jetbrains.com/qodana/feed " +
                    "--linter-slug qodana-jvm --version 2025.3 --build $build",
            )
        assertTrue(result.statusCode != 0, "gpg mode without a key must fail: ${result.output}")
        assertTrue(result.output.contains("gpg verify mode"), result.output)
    }

    @Test
    fun `--arch arm64 re-verifies the linuxARM64 link`(
        @TempDir tmp: Path,
    ) {
        val arm64Archive = "qodana-QDJVM-$build-aarch64.tar.gz"
        val arm64Link = "https://download.jetbrains.com/qodana/2025.3/$arm64Archive"
        val dualFeed =
            """
            {"Code":"QDJVM","Releases":[
              {"Date":"2025-09-15","Type":"release","Version":"2025.3.1","MajorVersion":"2025.3","Build":"$build",
               "Downloads":{
                 "linux":{"Link":"$link","ChecksumLink":"$link.sha256","Size":1},
                 "linuxARM64":{"Link":"$arm64Link","ChecksumLink":"$arm64Link.sha256","Size":1}}}
            ]}
            """.trimIndent()
        val runner =
            FakeCommandRunner().apply {
                on({ it.contains("curl") }) { argv ->
                    val out = Path.of(argv[argv.indexOf("-o") + 1])
                    val url = argv.last()
                    val body =
                        when {
                            url.endsWith(".releases.json") -> dualFeed
                            url.endsWith(".sha256.asc") -> "-----BEGIN PGP SIGNATURE-----"
                            url.endsWith(".sha256") -> "$goodSha  $arm64Archive\n"
                            else -> "archive-bytes"
                        }
                    Files.createDirectories(out.parent)
                    Files.writeString(out, body)
                    CommandResult(0, "", "")
                }
                on({ it.contains("--import") }, CommandResult(0, "", ""))
                on({ it.contains("--verify") }, CommandResult(0, "[GNUPG:] VALIDSIG $fingerprint 2025-09-15\n", ""))
                on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha  $arm64Archive\n", ""))
            }
        val key = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")
        val cmd = VerifyPinCommand(FeedClient(runner), DistVerifier(runner)) { null }
        val result = cmd.test(baseArgs("https://download.jetbrains.com/qodana/feed", key) + " --arch arm64")
        assertEquals(0, result.statusCode, result.output)
        assertTrue(
            runner.invocations.any { c -> c.contains("curl") && c.last() == arm64Link },
            "expected a curl of the arm64 archive link: ${runner.invocations.filter { it.contains("curl") }}",
        )
    }
}

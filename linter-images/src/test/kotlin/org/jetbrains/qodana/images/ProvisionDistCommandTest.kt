package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import org.jetbrains.qodana.images.dist.DistVerifier
import org.jetbrains.qodana.images.dist.Extractor
import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProvisionDistCommandTest {
    private val fingerprint = "B46DC71E03FEEB7F89D1F2491F7A8F87B9D8F501"
    private val goodSha = "a".repeat(64)
    private val build = "253.200"
    private val archiveName = "qodana-QDJVM-$build.tar.gz"
    private val link = "https://download.jetbrains.com/qodana/2025.3/$archiveName"

    private val feedBody =
        """
        {"Code":"QDJVM","Releases":[
          {"Date":"2025-09-15","Type":"release","Version":"2025.3.1","MajorVersion":"2025.3","Build":"$build",
           "Downloads":{"linux":{"Link":"$link","ChecksumLink":"$link.sha256","Size":1}}}
        ]}
        """.trimIndent()

    private class RecordingExtractor : Extractor {
        var archive: Path? = null
        var targetDir: Path? = null

        // Canonical Extractor: extractFlattened so targetDir IS the IDE root (matches the dist package).
        override fun extractFlattened(
            archive: Path,
            targetDir: Path,
        ) {
            this.archive = archive
            this.targetDir = targetDir
            Files.createDirectories(targetDir)
        }
    }

    // --gpg-fingerprint is always passed explicitly, so the key file alone is enough (no .fpr sibling).
    private fun gpgKey(tmp: Path): Path = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")

    // Canonical FakeCommandRunner: curl writes the file it is told to (-o <path>), gpg signer-matches,
    // sha256sum echoes the good digest. No Downloader port.
    private fun runner(): FakeCommandRunner =
        FakeCommandRunner().apply {
            on({ it.contains("curl") }) { argv ->
                val outIdx = argv.indexOf("-o")
                val out = Path.of(argv[outIdx + 1])
                val url = argv.last()
                val body =
                    when {
                        url.endsWith(".releases.json") -> feedBody
                        url.endsWith(".sha256.asc") -> "-----BEGIN PGP SIGNATURE-----"
                        url.endsWith(".sha256") -> "$goodSha *$archiveName\n"
                        else -> "archive-bytes"
                    }
                out.parent?.let { Files.createDirectories(it) }
                Files.writeString(out, body)
                CommandResult(0, "", "")
            }
            on({ it.contains("--import") }, CommandResult(0, "", ""))
            on({ it.contains("--verify") }, CommandResult(0, "[GNUPG:] VALIDSIG $fingerprint 2025-09-15\n", ""))
            on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha *$archiveName\n", ""))
        }

    private fun baseArgs(
        feedUrl: String,
        target: Path,
        key: Path,
    ) = listOf(
        "--distribution-feed", feedUrl,
        "--linter-slug", "qodana-jvm",
        "--version", "2025.3",
        "--build", build,
        "--gpg-key", key.toString(),
        "--gpg-fingerprint", fingerprint,
        "--target", target.toString(),
    )

    @Test
    fun `distribution-feed URL is forwarded verbatim to FeedClient`(
        @TempDir tmp: Path,
    ) {
        val customFeed = "https://custom.example.com/feed"
        val target = Files.createDirectories(tmp.resolve("staging"))
        val key = gpgKey(tmp)
        val runner = runner()

        // The runner records all invocations; we inspect the curl that fetches `.releases.json`
        // to verify the URL contains exactly the custom feed base.
        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(runner),
                verifier = DistVerifier(runner),
                extractor = RecordingExtractor(),
                getEnv = { null },
            )

        val result = command.test(baseArgs(customFeed, target, key))

        assertEquals(0, result.statusCode, result.output)
        val feedCurl = runner.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        val feedUrl = feedCurl.last { it.endsWith(".releases.json") }
        assertTrue(feedUrl.startsWith(customFeed), "expected feed URL to start with '$customFeed', got: $feedUrl")
    }

    @Test
    fun `token from getEnv is forwarded to FeedClient when set, null when unset`(
        @TempDir tmp: Path,
    ) {
        val key = gpgKey(tmp)
        val feedUrl = "https://download.jetbrains.com/qodana/feed"

        // Token present: the feed curl carries the bearer header.
        val runnerWithToken = runner()
        val targetWithToken = Files.createDirectories(tmp.resolve("staging-with-token"))
        val commandWithToken =
            ProvisionDistCommand(
                feedClient = FeedClient(runnerWithToken),
                verifier = DistVerifier(runnerWithToken),
                extractor = RecordingExtractor(),
                getEnv = { name -> if (name == ProvisionDistCommand.QD_FEED_TOKEN) "tok-123" else null },
            )
        val resultWithToken = commandWithToken.test(baseArgs(feedUrl, targetWithToken, key))
        assertEquals(0, resultWithToken.statusCode, resultWithToken.output)
        val feedCurlWithToken =
            runnerWithToken.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        assertTrue(feedCurlWithToken.any { it.contains("tok-123") }, "expected bearer token in curl args: $feedCurlWithToken")

        // Token absent: the feed curl has no Authorization header.
        val runnerNoToken = runner()
        val targetNoToken = Files.createDirectories(tmp.resolve("staging-no-token"))
        val commandNoToken =
            ProvisionDistCommand(
                feedClient = FeedClient(runnerNoToken),
                verifier = DistVerifier(runnerNoToken),
                extractor = RecordingExtractor(),
                getEnv = { null },
            )
        val resultNoToken = commandNoToken.test(baseArgs(feedUrl, targetNoToken, key))
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
        val target = Files.createDirectories(tmp.resolve("staging"))
        val key = gpgKey(tmp)

        // Runner that returns exit code 1 on all curl calls, simulating a 401/network error.
        val failingRunner =
            FakeCommandRunner().apply {
                on({ it.contains("curl") }, CommandResult(1, "", "HTTP 401 Unauthorized"))
            }

        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(failingRunner),
                verifier = DistVerifier(runner()),
                extractor = RecordingExtractor(),
                getEnv = { null },
            )

        // FeedClient.fetch calls require(exitCode == 0), which throws IllegalArgumentException.
        // Clikt's test() only catches CliktError, so the exception propagates — fail-closed is preserved.
        assertFailsWith<IllegalArgumentException> {
            command.test(baseArgs("https://packages.jetbrains.team/files/p/qd/private-feed", target, key))
        }
    }

    @Test
    fun `provisions extracts and accepts absent token`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("staging"))
        val runner = runner()
        val feedClient = FeedClient(runner)
        val verifier = DistVerifier(runner)
        val extractor = RecordingExtractor()
        val key = gpgKey(tmp)

        val command =
            ProvisionDistCommand(
                feedClient = feedClient,
                verifier = verifier,
                extractor = extractor,
                getEnv = { null },
            )

        val result = command.test(baseArgs("https://download.jetbrains.com/qodana/feed", target, key))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(archiveName, extractor.archive?.fileName?.toString())
        // Canonical: --target IS the IDE root (flattened), NOT target/idea.
        assertEquals(target, extractor.targetDir)
    }
}

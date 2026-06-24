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
        "--distribution-feed",
        feedUrl,
        "--linter-slug",
        "qodana-jvm",
        "--version",
        "2025.3",
        "--build",
        build,
        "--gpg-key",
        key.toString(),
        "--gpg-fingerprint",
        fingerprint,
        "--target",
        target.toString(),
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
                getEnv = { name -> if (name == QODANA_READ_SPACE_PACKAGES_TOKEN) "tok-123" else null },
            )
        val resultWithToken = commandWithToken.test(baseArgs(feedUrl, targetWithToken, key))
        assertEquals(0, resultWithToken.statusCode, resultWithToken.output)
        val feedCurlWithToken =
            runnerWithToken.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        assertTrue(
            feedCurlWithToken.any { it.contains("tok-123") },
            "expected bearer token in curl args: $feedCurlWithToken",
        )

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

        val extractor = RecordingExtractor()
        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(failingRunner),
                verifier = DistVerifier(runner()),
                extractor = extractor,
                getEnv = { null },
            )

        // Feed failure propagates as an exception (FeedClient throws on non-zero curl exit).
        // Clikt's test() only catches CliktError, so any other exception is unhandled — fail-closed.
        assertFailsWith<Exception> {
            command.test(baseArgs("https://packages.jetbrains.team/files/p/qd/private-feed", target, key))
        }
        // No side effects: the extractor was never reached.
        assertEquals(null, extractor.archive)
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

    private fun sha256Args(
        feedUrl: String,
        target: Path,
    ) = listOf(
        "--distribution-feed",
        feedUrl,
        "--linter-slug",
        "qodana-jvm",
        "--version",
        "2025.3",
        "--build",
        build,
        "--verify-mode",
        "sha256",
        "--target",
        target.toString(),
    )

    @Test
    fun `sha256 mode provisions with a token, no gpg, and never curls the asc`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("staging"))
        val runner = runner()
        val extractor = RecordingExtractor()
        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(runner),
                verifier = DistVerifier(runner),
                extractor = extractor,
                getEnv = { if (it == QODANA_READ_SPACE_PACKAGES_TOKEN) "read-tok" else null },
            )
        val result =
            command.test(
                sha256Args("https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed", target),
            )
        assertEquals(0, result.statusCode, result.output)
        assertEquals(archiveName, extractor.archive?.fileName?.toString())
        assertTrue(
            runner.invocations.none { c -> c.contains("curl") && c.last().endsWith(".asc") },
            "sha256 mode must not curl the .asc",
        )
        assertTrue(runner.invocations.none { it.firstOrNull() == "gpg" }, "sha256 mode must not run gpg")
    }

    @Test
    fun `sha256 mode hard-fails when QODANA_READ_SPACE_PACKAGES_TOKEN is absent`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("staging"))
        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(runner()),
                verifier = DistVerifier(runner()),
                extractor = RecordingExtractor(),
                getEnv = { null }, // no token
            )
        val result =
            command.test(
                sha256Args("https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed", target),
            )
        assertTrue(result.statusCode != 0, "missing token must hard-fail: ${result.output}")
        assertTrue(result.output.contains("QODANA_READ_SPACE_PACKAGES_TOKEN"), result.output)
    }

    @Test
    fun `gpg mode without a key fails at the verify-mode check`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("staging"))
        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(runner()),
                verifier = DistVerifier(runner()),
                extractor = RecordingExtractor(),
                getEnv = { null },
            )
        val result =
            command.test(
                listOf(
                    "--distribution-feed",
                    "https://download.jetbrains.com/qodana/feed",
                    "--linter-slug",
                    "qodana-jvm",
                    "--version",
                    "2025.3",
                    "--build",
                    build,
                    "--target",
                    target.toString(),
                ),
            )
        assertTrue(result.statusCode != 0, "gpg mode without a key must fail: ${result.output}")
        assertTrue(result.output.contains("gpg verify mode"), result.output)
    }

    @Test
    fun `--arch arm64 provisions the linuxARM64 download`(
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
                            url.endsWith(".sha256") -> "$goodSha *$arm64Archive\n"
                            else -> "archive-bytes"
                        }
                    out.parent?.let { Files.createDirectories(it) }
                    Files.writeString(out, body)
                    CommandResult(0, "", "")
                }
                on({ it.contains("--import") }, CommandResult(0, "", ""))
                on({ it.contains("--verify") }, CommandResult(0, "[GNUPG:] VALIDSIG $fingerprint 2025-09-15\n", ""))
                on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha *$arm64Archive\n", ""))
            }
        val target = Files.createDirectories(tmp.resolve("staging-arm64"))
        val extractor = RecordingExtractor()
        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(runner),
                verifier = DistVerifier(runner),
                extractor = extractor,
                getEnv = { null },
            )
        val result =
            command.test(baseArgs("https://download.jetbrains.com/qodana/feed", target, gpgKey(tmp)) + listOf("--arch", "arm64"))
        assertEquals(0, result.statusCode, result.output)
        assertEquals(arm64Archive, extractor.archive?.fileName?.toString())
    }
}

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

    @Test
    fun `public channel provisions extracts and needs no token`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("staging"))
        val runner = runner()
        val feedClient = FeedClient(runner) // canonical: curl through the runner, no Downloader/workDir
        val verifier = DistVerifier(runner)
        val extractor = RecordingExtractor()
        val key = gpgKey(tmp)

        val command =
            ProvisionDistCommand(
                feedClient = feedClient,
                verifier = verifier,
                extractor = extractor,
                getEnv = { null }, // no QD_FEED_TOKEN — and the public channel must not need it
            )

        val result =
            command.test(
                listOf(
                    "--feed-url", "https://download.jetbrains.com/qodana/feed",
                    "--linter-slug", "qodana-jvm",
                    "--version", "2025.3",
                    "--build", build,
                    "--channel", "public",
                    "--gpg-key", key.toString(),
                    "--gpg-fingerprint", fingerprint,
                    "--target", target.toString(),
                ),
            )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(archiveName, extractor.archive?.fileName?.toString())
        // Canonical: --target IS the IDE root (flattened), NOT target/idea.
        assertEquals(target, extractor.targetDir)
    }

    @Test
    fun `private channel without QD_FEED_TOKEN fails loudly`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("staging"))
        val feedClient = FeedClient(runner()) // canonical: curl through the CommandRunner, no Downloader
        val verifier = DistVerifier(runner())
        val extractor = RecordingExtractor()
        val key = gpgKey(tmp)

        val command =
            ProvisionDistCommand(
                feedClient = feedClient,
                verifier = verifier,
                extractor = extractor,
                getEnv = { null }, // QD_FEED_TOKEN unset
            )

        val result =
            command.test(
                listOf(
                    "--feed-url", "https://packages.jetbrains.team/files/p/qd/private-feed",
                    "--linter-slug", "qodana-jvm",
                    "--version", "2025.3",
                    "--build", build,
                    "--channel", "private",
                    "--gpg-key", key.toString(),
                    "--gpg-fingerprint", fingerprint,
                    "--target", target.toString(),
                ),
            )

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("QD_FEED_TOKEN"), result.output)
        // Nothing was provisioned: the extractor was never invoked.
        assertEquals(null, extractor.archive)
    }

    @Test
    fun `private channel with QD_FEED_TOKEN adds the bearer header on the feed curl`(
        @TempDir tmp: Path,
    ) {
        val target = Files.createDirectories(tmp.resolve("staging"))
        val runner = runner() // canonical FakeCommandRunner serving feed/sha256/asc/archive + gpg/sha256sum
        val command =
            ProvisionDistCommand(
                feedClient = FeedClient(runner),
                verifier = DistVerifier(runner),
                extractor = RecordingExtractor(),
                getEnv = { name -> if (name == "QD_FEED_TOKEN") "tok-123" else null },
            )

        val result =
            command.test(
                listOf(
                    "--feed-url", "https://packages.jetbrains.team/files/p/qd/private-feed",
                    "--linter-slug", "qodana-jvm",
                    "--version", "2025.3",
                    "--build", build,
                    "--channel", "private",
                    "--gpg-key", gpgKey(tmp).toString(),
                    "--gpg-fingerprint", fingerprint,
                    "--target", target.toString(),
                ),
            )

        assertEquals(0, result.statusCode, result.output)
        // The feed curl carries the bearer token; assert it appears in the recorded argv.
        val feedCurl = runner.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        assertTrue(feedCurl.any { it.contains("tok-123") }, feedCurl.toString())
    }
}

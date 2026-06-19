package org.jetbrains.qodana.images

import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * bump-pins rewrites ONLY `QD_BUILD` within the pinned major, never `QD_VERSION` (which is
 * contractually the MAJOR — EnvContractTest pins it byte-identical to phase-0-decisions.md). It picks
 * the newest within-major release of the pinned `QD_RELEASE_TYPE` and never crosses majors. The feed
 * is read per-`.env` from `QD_DISTRIBUTION_FEED` (falling back to [DEFAULT_DISTRIBUTION_FEED]).
 */
class BumpPinsCommandTest {
    private fun feedRunner(body: String) =
        FakeCommandRunner().apply {
            on({ it.contains("curl") }) { argv ->
                File(argv[argv.indexOf("-o") + 1]).writeText(body)
                CommandResult(0, "", "")
            }
        }

    /** A runner that serves a different feed body per requested feed-base URL (matched on the curl URL). */
    private fun feedRunnerByUrl(bodyByFeed: Map<String, String>) =
        FakeCommandRunner().apply {
            on({ it.contains("curl") }) { argv ->
                val url = argv.last { it.endsWith(".releases.json") }
                val body = bodyByFeed.entries.first { url.startsWith(it.key) }.value
                File(argv[argv.indexOf("-o") + 1]).writeText(body)
                CommandResult(0, "", "")
            }
        }

    @Test
    fun `rewrites within-major build but never crosses majors and keeps QD_VERSION the major`(
        @TempDir dir: File,
    ) {
        // Canonical .env: QD_VERSION is the major; QD_BUILD is the exact pin.
        val env =
            File(dir, "qodana-jvm.env").apply {
                writeText(
                    """
                    QD_LINTER_SLUG=qodana-jvm
                    QD_VERSION=2025.3
                    QD_BUILD=253.1000
                    QD_RELEASE_TYPE=release
                    """.trimIndent(),
                )
            }
        // Feed: a newer within-major build (253.2000, type release) plus a cross-major 261.500.
        val runner =
            feedRunner(
                """{"Code":"QDJVM","Releases":[
                  {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}},
                  {"Date":"2026-02-01","Type":"release","Version":"2026.1.0","MajorVersion":"2026.1","Build":"261.500","Downloads":{}}
                ]}""",
            )
        BumpPinsCommand(FeedClient(runner)).rewrite(env.parentFile.toPath())
        val text = env.readText()
        assertTrue(text.contains("QD_BUILD=253.2000"), text)
        // QD_VERSION must stay the MAJOR (2025.3), never the full release version (2025.3.2).
        assertTrue(text.contains("QD_VERSION=2025.3\n"), "QD_VERSION stays the major: $text")
        assertTrue(!text.contains("QD_VERSION=2025.3.2"), "QD_VERSION must not become the full version: $text")
        assertTrue(!text.contains("261.500"), "cross-major build must be ignored: $text")
    }

    @Test
    fun `picks the newest within-major by date and ignores non-matching release types`(
        @TempDir dir: File,
    ) {
        val env =
            File(dir, "qodana-jvm.env").apply {
                writeText(
                    """
                    QD_LINTER_SLUG=qodana-jvm
                    QD_VERSION=2025.3
                    QD_BUILD=253.1000
                    QD_RELEASE_TYPE=release
                    """.trimIndent(),
                )
            }
        // Newest by Date is an EAP (must be ignored for QD_RELEASE_TYPE=release); newest RELEASE is 253.2000.
        val runner =
            feedRunner(
                """{"Code":"QDJVM","Releases":[
                  {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}},
                  {"Date":"2025-12-01","Type":"eap","Version":"2025.3.3","MajorVersion":"2025.3","Build":"253.3000","Downloads":{}}
                ]}""",
            )
        BumpPinsCommand(FeedClient(runner)).rewrite(env.parentFile.toPath())
        val text = env.readText()
        assertTrue(text.contains("QD_BUILD=253.2000"), text)
        assertTrue(!text.contains("253.3000"), "EAP build must be ignored for QD_RELEASE_TYPE=release: $text")
    }

    @Test
    fun `leaves clang-style env without an IDE dist untouched`(
        @TempDir dir: File,
    ) {
        val env =
            File(dir, "qodana-clang.env").apply {
                writeText(
                    """
                    QD_BASE_IMAGE=dhi.io/debian-base:bookworm@sha256:abc
                    CLI_BINARY=qodana-clang
                    """.trimIndent(),
                )
            }
        val before = env.readText()
        // No QD_LINTER_SLUG → nothing to fetch/bump; the feed runner must never be consulted.
        BumpPinsCommand(FeedClient(FakeCommandRunner())).rewrite(env.parentFile.toPath())
        assertTrue(env.readText() == before, "clang .env (no IDE dist) must be left byte-identical")
    }

    @Test
    fun `the feed URL is read per-env from QD_DISTRIBUTION_FEED and used for the fetch`(
        @TempDir dir: File,
    ) {
        val customFeed = "https://custom.example.com/feed"
        File(dir, "qodana-jvm.env").writeText(
            """
            QD_LINTER_SLUG=qodana-jvm
            QD_VERSION=2025.3
            QD_BUILD=253.1000
            QD_RELEASE_TYPE=release
            QD_DISTRIBUTION_FEED=$customFeed
            """.trimIndent(),
        )
        val runner =
            feedRunner(
                """{"Code":"QDJVM","Releases":[
                  {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}}
                ]}""",
            )
        BumpPinsCommand(FeedClient(runner)).rewrite(dir.toPath())
        val feedCurl = runner.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        val feedUrl = feedCurl.last { it.endsWith(".releases.json") }
        assertTrue(feedUrl.startsWith(customFeed), "expected feed URL to start with '$customFeed', got: $feedUrl")
    }

    @Test
    fun `same slug-major-type but different QD_DISTRIBUTION_FEED resolve independently`(
        @TempDir dir: File,
    ) {
        val feedA = "https://feed-a.example.com/feed"
        val feedB = "https://feed-b.example.com/feed"
        // Two files with the SAME slug/major/releaseType differing ONLY in their feed: each must fetch
        // its own feed and may land on a different build (the dedup key now includes the feed).
        File(dir, "qodana-jvm-a.env").writeText(
            """
            QD_LINTER_SLUG=qodana-jvm
            QD_VERSION=2025.3
            QD_BUILD=253.1000
            QD_RELEASE_TYPE=release
            QD_DISTRIBUTION_FEED=$feedA
            """.trimIndent(),
        )
        File(dir, "qodana-jvm-b.env").writeText(
            """
            QD_LINTER_SLUG=qodana-jvm
            QD_VERSION=2025.3
            QD_BUILD=253.1000
            QD_RELEASE_TYPE=release
            QD_DISTRIBUTION_FEED=$feedB
            """.trimIndent(),
        )
        val runner =
            feedRunnerByUrl(
                mapOf(
                    feedA to """{"Code":"QDJVM","Releases":[
                      {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}}
                    ]}""",
                    feedB to """{"Code":"QDJVM","Releases":[
                      {"Date":"2025-11-01","Type":"release","Version":"2025.3.5","MajorVersion":"2025.3","Build":"253.5000","Downloads":{}}
                    ]}""",
                ),
            )
        BumpPinsCommand(FeedClient(runner)).rewrite(dir.toPath())
        assertTrue(File(dir, "qodana-jvm-a.env").readText().contains("QD_BUILD=253.2000"), "feed A build")
        assertTrue(File(dir, "qodana-jvm-b.env").readText().contains("QD_BUILD=253.5000"), "feed B build")
        // Two distinct feeds → two fetches (the dedup key must NOT collapse them).
        assertEquals(2, runner.invocations.count { it.contains("curl") }, runner.invocations.toString())
    }

    @Test
    fun `jvm and android sharing the pin resolve once and agree on the new build`(
        @TempDir dir: File,
    ) {
        val shared =
            { name: String ->
                File(dir, name).writeText(
                    """
                    QD_LINTER_SLUG=qodana-jvm
                    QD_VERSION=2025.3
                    QD_BUILD=253.1000
                    QD_RELEASE_TYPE=release
                    """.trimIndent(),
                )
            }
        shared("qodana-jvm.env")
        shared("qodana-android.env")
        val runner =
            feedRunner(
                """{"Code":"QDJVM","Releases":[
                  {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}}
                ]}""",
            )
        BumpPinsCommand(FeedClient(runner)).rewrite(dir.toPath())
        // Both files bumped to the SAME build (no mid-run divergence).
        assertTrue(File(dir, "qodana-jvm.env").readText().contains("QD_BUILD=253.2000"))
        assertTrue(File(dir, "qodana-android.env").readText().contains("QD_BUILD=253.2000"))
        // The shared pin is resolved ONCE — exactly one feed fetch (curl) for both files.
        assertEquals(1, runner.invocations.count { it.contains("curl") }, runner.invocations.toString())
    }

    @Test
    fun `forwards the QD_FEED_TOKEN bearer to the feed fetch when set`(
        @TempDir dir: File,
    ) {
        File(dir, "qodana-jvm.env").writeText(
            """
            QD_LINTER_SLUG=qodana-jvm
            QD_VERSION=2025.3
            QD_BUILD=253.1000
            QD_RELEASE_TYPE=release
            """.trimIndent(),
        )
        val runner =
            feedRunner(
                """{"Code":"QDJVM","Releases":[
                  {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}}
                ]}""",
            )
        BumpPinsCommand(FeedClient(runner), getEnv = { name -> if (name == "QD_FEED_TOKEN") "tok-123" else null })
            .rewrite(dir.toPath())
        val feedCurl = runner.invocations.first { it.contains("curl") && it.any { a -> a.endsWith(".releases.json") } }
        assertTrue(feedCurl.any { it.contains("tok-123") }, "expected bearer token in curl args: $feedCurl")
    }

    @Test
    fun `syncs the QODANA_SLUG_BUILD row in the decisions doc and leaves prose untouched`(
        @TempDir dir: File,
    ) {
        File(dir, "qodana-jvm.env").writeText(
            """
            QD_LINTER_SLUG=qodana-jvm
            QD_VERSION=2025.3
            QD_BUILD=253.1000
            QD_RELEASE_TYPE=release
            """.trimIndent(),
        )
        val decisions =
            File(dir, "decisions.md").apply {
                // A real `KEY = value` row plus a prose line that merely mentions the key in backticks.
                writeText(
                    """
                    The pin `QODANA_JVM_BUILD` must match the .env.

                    QODANA_JVM_VERSION = 2025.3
                    QODANA_JVM_BUILD = 253.1000
                    """.trimIndent(),
                )
            }
        val runner =
            feedRunner(
                """{"Code":"QDJVM","Releases":[
                  {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}}
                ]}""",
            )
        BumpPinsCommand(FeedClient(runner)).rewrite(dir.toPath(), decisions.toPath())
        val text = decisions.readText()
        assertTrue(text.contains("QODANA_JVM_BUILD = 253.2000"), "decisions build row synced: $text")
        assertTrue(text.contains("The pin `QODANA_JVM_BUILD` must match"), "prose mention untouched: $text")
        assertTrue(text.contains("QODANA_JVM_VERSION = 2025.3"), "version row untouched: $text")
    }

    @Test
    fun `normalizes a hyphenated community slug to QODANA_JVM_COMMUNITY_BUILD in the decisions doc`(
        @TempDir dir: File,
    ) {
        File(dir, "qodana-jvm-community.env").writeText(
            """
            QD_LINTER_SLUG=qodana-jvm-community
            QD_VERSION=2025.3
            QD_BUILD=253.1000
            QD_RELEASE_TYPE=release
            """.trimIndent(),
        )
        val decisions =
            File(dir, "decisions.md").apply {
                writeText(
                    """
                    QODANA_JVM_COMMUNITY_VERSION = 2025.3
                    QODANA_JVM_COMMUNITY_BUILD = 253.1000
                    """.trimIndent(),
                )
            }
        val runner =
            feedRunner(
                """{"Code":"QDJVMC","Releases":[
                  {"Date":"2025-10-01","Type":"release","Version":"2025.3.2","MajorVersion":"2025.3","Build":"253.2000","Downloads":{}}
                ]}""",
            )
        BumpPinsCommand(FeedClient(runner)).rewrite(dir.toPath(), decisions.toPath())
        val text = decisions.readText()
        // Hyphens in the slug normalize to underscores: qodana-jvm-community → QODANA_JVM_COMMUNITY_BUILD.
        assertTrue(text.contains("QODANA_JVM_COMMUNITY_BUILD = 253.2000"), "community build row synced: $text")
    }
}

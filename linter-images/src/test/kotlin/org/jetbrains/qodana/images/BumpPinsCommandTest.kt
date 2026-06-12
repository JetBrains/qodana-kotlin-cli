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
 * the newest within-major release of the pinned `QD_RELEASE_TYPE` and never crosses majors.
 */
class BumpPinsCommandTest {
    private fun feedRunner(body: String) =
        FakeCommandRunner().apply {
            on({ it.contains("curl") }) { argv ->
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
}

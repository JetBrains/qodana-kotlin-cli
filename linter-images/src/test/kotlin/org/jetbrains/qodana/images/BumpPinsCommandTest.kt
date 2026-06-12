package org.jetbrains.qodana.images

import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
}

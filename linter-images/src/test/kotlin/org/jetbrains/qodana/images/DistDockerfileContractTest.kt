package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * True when `flag` appears as a whole option token in a subcommand's `--help` output. Clikt renders
 * each option as `--flag` or `--flag=<TYPE>`, bounded by whitespace or `=`, so the flag is matched as
 * a whole token: a plain `help.contains(flag)` would wrongly accept `--dist` against a help that only
 * lists `--distribution-feed` (the most likely option to regress here).
 */
internal fun isFlagAccepted(
    flag: String,
    help: String,
): Boolean = Regex("(?<![A-Za-z0-9-])" + Regex.escape(flag) + "(?![A-Za-z0-9-])").containsMatchIn(help)

/**
 * Guards that every `--flag` the dist.dockerfile passes to an `image-tool` subcommand is a flag that
 * subcommand actually accepts. The unit suite otherwise has no check that the dockerfile and the CLI
 * agree on options, so a renamed/removed option (e.g. --feed-url → --distribution-feed) would only
 * surface as a NoSuchOption hard-fail during the amd64-only image build.
 */
class DistDockerfileContractTest {
    // Test working dir is the module root (pinned in build.gradle.kts) — resolve relative to it.
    private val dockerfile = Path.of("docker/lib/dist.dockerfile").readText()

    /**
     * Collect the `--flag` tokens passed to a given `image-tool <subcommand>` invocation. The call is
     * a `\`-continued shell command inside a heredoc, so we read from the first `image-tool
     * <subcommand>` line until the line that does NOT end in a backslash continuation.
     */
    private fun flagsPassedTo(subcommand: String): List<String> {
        val lines = dockerfile.lines()
        val start = lines.indexOfFirst { it.contains("image-tool $subcommand") }
        require(start >= 0) { "dist.dockerfile does not invoke image-tool $subcommand" }

        val invocation = StringBuilder()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            invocation.append(line.trimEnd().removeSuffix("\\")).append(' ')
            if (!line.trimEnd().endsWith("\\")) break
            i++
        }
        return Regex("""--[A-Za-z0-9][A-Za-z0-9-]*""")
            .findAll(invocation)
            .map { it.value }
            .toList()
    }

    private fun assertEveryFlagAccepted(subcommand: String) {
        val flags = flagsPassedTo(subcommand)
        assertTrue(flags.isNotEmpty(), "expected to parse $subcommand flags from dist.dockerfile")
        val help = buildImageTool().test(listOf(subcommand, "--help")).output
        for (flag in flags) {
            assertTrue(
                isFlagAccepted(flag, help),
                "dist.dockerfile passes $flag to $subcommand, but $subcommand --help does not accept it:\n$help",
            )
        }
    }

    @Test
    fun `every flag dist dockerfile passes to provision-dist is accepted by the command`() {
        assertEveryFlagAccepted("provision-dist")
    }

    @Test
    fun `every flag dist dockerfile passes to verify-dist-layout is accepted by the command`() {
        assertEveryFlagAccepted("verify-dist-layout")
    }

    /**
     * The dockerfile re-declares `ARG QD_DISTRIBUTION_FEED=<default>` twice (pre-FROM and in
     * dist-builder). Both defaults must stay byte-identical to [DEFAULT_DISTRIBUTION_FEED]: the flag-name
     * guards above don't check the default VALUE, so a future edit to the Kotlin const would silently
     * diverge from the dockerfile's hard-coded default.
     */
    @Test
    fun `dist dockerfile QD_DISTRIBUTION_FEED defaults equal the const`() {
        val defaults =
            Regex("""(?m)^ARG QD_DISTRIBUTION_FEED=(.+)$""")
                .findAll(dockerfile)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(2, defaults.size, "expected two ARG QD_DISTRIBUTION_FEED=<default> lines in dist.dockerfile")
        for (value in defaults) {
            assertEquals(DEFAULT_DISTRIBUTION_FEED, value)
        }
    }

    @Test
    fun `isFlagAccepted matches a flag listed in help`() {
        val help = buildImageTool().test(listOf("provision-dist", "--help")).output
        assertTrue(isFlagAccepted("--distribution-feed", help), help)
    }

    @Test
    fun `isFlagAccepted rejects a flag that is only a prefix of a listed flag`() {
        // provision-dist lists --distribution-feed but NOT --dist. A substring check wrongly accepts
        // --dist; the whole-token match must reject it, or --dist could regress unnoticed.
        val help = buildImageTool().test(listOf("provision-dist", "--help")).output
        assertTrue(isFlagAccepted("--distribution-feed", help), help)
        assertFalse(isFlagAccepted("--dist", help), help)
    }
}

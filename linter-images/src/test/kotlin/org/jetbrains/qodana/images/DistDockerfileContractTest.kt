package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

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
     * a `\`-continued shell command inside a heredoc, so we read from the subcommand name until the
     * line that does NOT end in a backslash continuation.
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
                help.contains(flag),
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
}

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
     * The shadowing fix (QD-15032): neither QD_DISTRIBUTION_FEED nor QD_VERIFY_MODE may carry a
     * `=default` ARG. dockerfile-x emits each INCLUDE_ARGS .env key as a GLOBAL `ARG NAME="val"` at the
     * top; a later `ARG NAME=default` (global OR in dist-builder) CLOBBERS that override back to the
     * default (verified: qodana-js.env's QODANA_UID note). So the dist-builder re-declares both as BARE
     * ARGs (which inherit the INCLUDE_ARGS override when a slug's .env sets it) and the provision-dist
     * RUN supplies the public default via `${NAME:-default}` shell expansion below.
     */
    @Test
    fun `dist-builder re-declares feed and verify-mode as bare ARGs so an env override survives`() {
        assertTrue(
            Regex("""(?m)^\s*ARG QD_DISTRIBUTION_FEED\s*$""").containsMatchIn(dockerfile),
            "QD_DISTRIBUTION_FEED must be a BARE ARG (no =default), else an INCLUDE_ARGS .env override is clobbered",
        )
        assertTrue(
            Regex("""(?m)^\s*ARG QD_VERIFY_MODE\s*$""").containsMatchIn(dockerfile),
            "QD_VERIFY_MODE must be a BARE ARG (no =default), else an INCLUDE_ARGS .env override is clobbered",
        )
        assertFalse(
            Regex("""(?m)^\s*ARG QD_DISTRIBUTION_FEED=""").containsMatchIn(dockerfile),
            "no defaulted QD_DISTRIBUTION_FEED ARG (it would shadow the .env override)",
        )
        assertFalse(
            Regex("""(?m)^\s*ARG QD_VERIFY_MODE=""").containsMatchIn(dockerfile),
            "no defaulted QD_VERIFY_MODE ARG (it would shadow the .env override)",
        )
    }

    /**
     * Public images omit both keys, so the provision-dist RUN must supply the defaults via set-u-safe
     * `${NAME:-default}` shell expansion: the public feed (byte-identical to [DEFAULT_DISTRIBUTION_FEED])
     * and gpg verification. A slug's .env override (INCLUDE_ARGS -> bare ARG) wins over the `:-` fallback.
     */
    @Test
    fun `provision-dist RUN supplies public feed and gpg defaults via set-u-safe expansion`() {
        assertTrue(
            dockerfile.contains("--distribution-feed \"\${QD_DISTRIBUTION_FEED:-$DEFAULT_DISTRIBUTION_FEED}\""),
            "provision-dist must default to the public feed via \${QD_DISTRIBUTION_FEED:-...}:\n$dockerfile",
        )
        assertTrue(
            dockerfile.contains("--verify-mode \"\${QD_VERIFY_MODE:-gpg}\""),
            "provision-dist must default to gpg via \${QD_VERIFY_MODE:-gpg}:\n$dockerfile",
        )
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

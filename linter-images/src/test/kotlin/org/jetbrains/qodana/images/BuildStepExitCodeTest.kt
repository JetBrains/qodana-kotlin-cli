package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Regression for QD-15439: bash disables errexit inside a `( ... )` used as an `if` condition. In e2e
 * mode a failing `docker compose build` therefore fell through to `docker builder prune -af` (exit 0),
 * and THAT became the subshell's status — masking a genuine build failure as success and starving the
 * retry of its transient (75) signal. This executes the real "Build" step body (with `docker` stubbed),
 * proving exit-code propagation rather than string-matching the source.
 */
class BuildStepExitCodeTest {
    private val actionPath = Path.of("../.github/actions/build-linter-image/action.yaml")
    private val action: JsonNode = YAMLMapper().readTree(actionPath.readText())

    /** The "Build" retry step's `with.run`, with the GH `${{ ... }}` placeholders substituted for e2e mode. */
    private fun e2eBuildBody(): String {
        val step =
            action["runs"]["steps"].single {
                it["name"]?.asText()?.startsWith("Build \${{ inputs.image }}") == true
            }
        return step["with"]["run"]
            .asText()
            .replace("\${{ inputs.push-registry }}", "") // empty = e2e mode
            .replace("\${{ inputs.compose-files }}", "-f compose.yaml")
            .replace("\${{ inputs.image }}", "qodana-x")
            .replace("\${{ inputs.arch }}", "amd64")
    }

    /** Runs the extracted body under bash with a stubbed `docker` on PATH; returns (exit code, combined output). */
    private fun run(
        composeExit: Int,
        composeStderr: String,
        pruneExit: Int = 0,
    ): Pair<Int, String> {
        val bin = Files.createTempDirectory("docker-stub")
        val docker = bin.resolve("docker")
        docker.writeText(
            """
            #!/usr/bin/env bash
            if [ "$1" = "compose" ]; then
                printf '%s\n' '$composeStderr' >&2
                exit $composeExit
            elif [ "$1" = "builder" ]; then
                exit $pruneExit
            else
                echo "stub docker: unexpected invocation: $*" >&2
                exit 99
            fi
            """.trimIndent(),
        )
        docker.toFile().setExecutable(true)

        val pb = ProcessBuilder("bash", "-c", e2eBuildBody())
        pb.redirectErrorStream(true)
        pb.environment()["PATH"] = "$bin:${pb.environment()["PATH"]}"
        pb.environment()["BUILD_ARGS"] = ""
        // This machine's BASH_ENV points at a profile that re-prepends /opt/homebrew/bin (real `docker`)
        // ahead of anything set above — unset it so the stub `docker` on PATH actually wins.
        pb.environment().remove("BASH_ENV")
        val p = pb.start()
        val out = p.inputStream.readBytes().decodeToString()
        return p.waitFor() to out
    }

    @Test
    fun `genuine e2e build failure propagates its real exit code, not masked to 0 by prune`() {
        val (code, out) =
            run(
                composeExit = 100,
                composeStderr = "failed to solve: process \"...\" did not complete successfully: exit code 100",
            )
        assertEquals(100, code, "a genuine compose-build failure must surface, not be masked as success: $out")
    }

    @Test
    fun `transient e2e build flake is classified 75 so the retry fires`() {
        val (code, out) =
            run(composeExit = 1, composeStderr = "received unexpected HTTP status: 503 Service Unavailable")
        assertEquals(75, code, "a transient compose-build flake must surface as 75 to trigger a retry: $out")
    }

    @Test
    fun `successful e2e build (compose build + prune both exit 0) exits 0`() {
        val (code, out) = run(composeExit = 0, composeStderr = "", pruneExit = 0)
        assertEquals(0, code, out)
    }
}

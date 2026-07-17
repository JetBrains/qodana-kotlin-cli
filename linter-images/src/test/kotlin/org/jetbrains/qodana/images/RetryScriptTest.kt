package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Drives .github/actions/retry/retry.sh as a process (env-driven), asserting outcomes, not timings. */
class RetryScriptTest {
    private val script = Path.of("../.github/actions/retry/retry.sh").toAbsolutePath().normalize()

    private fun hasTimeout(): Boolean =
        listOf("timeout", "gtimeout").any {
            ProcessBuilder("bash", "-c", "command -v $it").start().waitFor() == 0
        }

    private fun run(
        runBody: String,
        env: Map<String, String>,
    ): Pair<Int, String> {
        val pb = ProcessBuilder("bash", script.toString()).redirectErrorStream(true)
        pb.environment()["RUN"] = runBody
        pb.environment()["TIMEOUT_SECONDS"] = env["TIMEOUT_SECONDS"] ?: "5"
        pb.environment()["INITIAL_DELAY_SECONDS"] = env["INITIAL_DELAY_SECONDS"] ?: "0"
        pb.environment()["MAX_DELAY_SECONDS"] = env["MAX_DELAY_SECONDS"] ?: "0"
        pb.environment()["WHAT"] = "test"
        env["SLEEP_CMD"]?.let { pb.environment()["SLEEP_CMD"] = it }
        val p = pb.start()
        val out = p.inputStream.readBytes().decodeToString()
        return p.waitFor() to out
    }

    @Test
    fun `succeeds without retry when the command exits 0`() {
        val counter = Files.createTempFile("retry", ".cnt")
        val (code, _) = run("printf x >> '$counter'; exit 0", emptyMap())
        assertEquals(0, code)
        assertEquals(1, Files.readString(counter).length, "must run exactly once")
    }

    @Test
    fun `retries on 75 and succeeds once the command stops asking`() {
        val counter = Files.createTempFile("retry", ".cnt")
        Files.writeString(counter, "")
        val body = "printf x >> '$counter'; n=\$(wc -c < '$counter'); [ \"\$n\" -lt 3 ] && exit 75; exit 0"
        val (code, _) = run(body, mapOf("TIMEOUT_SECONDS" to "10"))
        assertEquals(0, code, "must eventually succeed")
        assertEquals(3, Files.readString(counter).length, "must run 3 times (2x75 + success)")
    }

    @Test
    fun `does not retry a genuine (non-75) failure`() {
        val counter = Files.createTempFile("retry", ".cnt")
        val (code, _) = run("printf x >> '$counter'; exit 1", mapOf("TIMEOUT_SECONDS" to "10"))
        assertEquals(1, code, "must pass the real exit code straight through")
        assertEquals(1, Files.readString(counter).length, "must run exactly once — no retry on a real error")
    }

    @Test
    fun `gives up red at the deadline while still asking for retry`() {
        val (code, out) = run("exit 75", mapOf("TIMEOUT_SECONDS" to "0"))
        assertEquals(75, code, "must surface the transient give-up as a red exit")
        assertTrue(out.contains("giving up") || out.contains("budget exhausted"), "must log a loud give-up: $out")
    }

    @Test
    fun `backoff doubles then caps (no real sleeps via the SLEEP_CMD seam)`() {
        // Exits 75 three times then succeeds; SLEEP_CMD=':' makes backoff instant so we assert the delay
        // sequence from the logs, not wall-clock time.
        val counter = Files.createTempFile("retry", ".cnt")
        Files.writeString(counter, "")
        val body = "printf x >> '$counter'; n=\$(wc -c < '$counter'); [ \"\$n\" -lt 4 ] && exit 75; exit 0"
        val env =
            mapOf(
                "TIMEOUT_SECONDS" to "100",
                "INITIAL_DELAY_SECONDS" to "1",
                "MAX_DELAY_SECONDS" to "2",
                "SLEEP_CMD" to ":",
            )
        val (code, out) = run(body, env)
        assertEquals(0, code)
        val delays = Regex("retrying in (\\d+)s").findAll(out).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1", "2", "2"), delays, "backoff must double (1→2) then cap at max-delay (2): $out")
    }

    @Test
    fun `kills a hung attempt at the deadline (requires a timeout binary)`() {
        assumeTrue(hasTimeout(), "no timeout/gtimeout on PATH (local macOS without coreutils) — skipping hang bound")
        val (code, out) = run("sleep 30", mapOf("TIMEOUT_SECONDS" to "1"))
        assertEquals(124, code, "a hung attempt must be killed at the budget (exit 124): $out")
    }
}

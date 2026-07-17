package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/** The inlined transient classifiers are the sole retry-vs-fail-fast decision; validate each real pattern. */
class TransientClassificationTest {
    private val files =
        listOf(
            "../.github/actions/build-linter-image/action.yaml",
            "../.github/workflows/publish-image.yaml",
        )

    // pattern -> its `grep -qE '...'` extracted from every retry step in the two files
    private fun patterns(): List<String> =
        files.flatMap { file ->
            Regex("""grep -qE '([^']*)'""").findAll(Path.of(file).readText()).map { m -> m.groupValues[1] }.toList()
        }

    private fun collectRetryRuns(
        node: JsonNode,
        out: MutableList<String>,
    ) {
        if (node.isObject) {
            if (node["uses"]?.asText() == "./.github/actions/retry") node["with"]?.get("run")?.asText()?.let(out::add)
            node.properties().forEach { collectRetryRuns(it.value, out) }
        } else if (node.isArray) {
            node.forEach { collectRetryRuns(it, out) }
        }
    }

    private fun retryRunBodies(): List<String> {
        val out = mutableListOf<String>()
        files.forEach { collectRetryRuns(YAMLMapper().readTree(Path.of(it).readText()), out) }
        return out
    }

    private fun matches(
        pattern: String,
        sample: String,
    ): Boolean {
        val script = "printf '%s' \"\$0\" | grep -qE \"\$1\""
        return ProcessBuilder("bash", "-c", script, sample, pattern).start().waitFor() == 0
    }

    private val gradleTransient =
        listOf(
            "Received status code 403 from server: Forbidden",
            "Received status code 429 from server",
            "Received status code 503 from server",
            "Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.3.20",
            "Could not GET 'https://repo.maven.apache.org/…'",
            "Connection reset",
            "Read timed out",
            "Connect timed out",
            "TLS handshake timeout",
        )
    private val gradleGenuine =
        listOf(
            "e: file.kt: unresolved reference: foo",
            "> Task :compileKotlin FAILED",
            "BUILD FAILED in 12s",
        )
    private val dockerTransient =
        listOf(
            "received unexpected HTTP status: 503 Service Unavailable",
            "received unexpected HTTP status: 429 Too Many Requests",
            "TLS handshake timeout",
            "connection refused",
        )
    private val dockerGenuine =
        listOf(
            "manifest unknown",
            "unauthorized: authentication required",
            "denied: requested access to the resource is denied",
        )

    @Test
    fun `each transient sample matches at least one classifier and no genuine sample matches`() {
        val pats = patterns()
        assertTrue(pats.size >= 5, "expected a classifier per retry step; found ${pats.size}")
        (gradleTransient + dockerTransient).forEach { s ->
            assertTrue(pats.any { matches(it, s) }, "transient sample not classified as transient: '$s'")
        }
        (gradleGenuine + dockerGenuine).forEach { s ->
            assertTrue(pats.none { matches(it, s) }, "genuine failure wrongly classified transient: '$s'")
        }
    }

    @Test
    fun `no classifier reads rc after a bare fi (the exit-code-reset bug)`() {
        val bodies = retryRunBodies()
        assertTrue(bodies.size >= 5, "expected >=5 retry classifiers (3 build + 2 merge); found ${bodies.size}")
        bodies.forEach { body ->
            val squashed = body.replace(Regex("\\s+"), " ")
            // `$?` right after a bare `fi` reads 0 (the if-statement's own status), silently turning a genuine
            // failure into a reported success. The real exit code must be captured in an `else`.
            assertFalse(
                squashed.contains(Regex("""\bfi\b\s*;?\s*rc=\$\?""")),
                "rc=\$? must be captured in an else branch, not after `fi`: $body",
            )
            assertTrue(body.contains("rc=\$?"), "classifier must capture + pass through the real exit code: $body")
        }
    }
}

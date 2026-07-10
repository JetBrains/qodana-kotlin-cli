package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Repo-wide CI-contract invariants for the subsystem workflows (checks/cli/images/pr-title) and the
 * two aggregation gate jobs. Lives in the linter-images module — the established home for GitHub
 * Actions YAML contract tests (reads repo-root workflows via `../`). Complements the image-specific
 * LinterImagesWorkflowContractTest.
 */
class CiWorkflowContractTest {
    private fun wf(file: String): JsonNode =
        YAMLMapper().readTree(Path.of("../.github/workflows/$file").readText())

    /** A gate job: aggregates a matrix/job set into one stable required check via re-actors/alls-green. */
    private fun assertGate(file: String, jobId: String, displayName: String, needs: List<String>) {
        val job = wf(file)["jobs"][jobId] ?: error("$file must define gate job '$jobId'")
        assertEquals(displayName, job["name"].asText(), "$jobId gate display name")
        assertEquals("always()", job["if"].asText(), "$jobId gate must run with if: always()")
        val declaredNeeds = job["needs"].map { it.asText() }.toSet()
        assertEquals(needs.toSet(), declaredNeeds, "$jobId gate needs")
        val gateStep = job["steps"].firstOrNull { it["uses"]?.asText()?.startsWith("re-actors/alls-green") == true }
        assertTrue(gateStep != null, "$jobId gate must use re-actors/alls-green")
        // Strict handling is deliberate: with neither `allowed-skips` nor `allowed-failures`, a skipped OR
        // failed needed job fails the gate — a partial-matrix run can never silently green the required check.
        // (alls-green's job_matrix_succeeded excludes jobs named in EITHER input, so both must be forbidden.)
        listOf("allowed-skips", "allowed-failures").forEach { input ->
            val v = gateStep!!["with"]?.get(input)
            assertTrue(
                v == null || v.asText().isBlank(),
                "$jobId gate must not set $input (would let a skipped/failed needed job pass): $v",
            )
        }
    }

    @Test
    fun `images gate aggregates the e2e matrix`() {
        assertGate("images.yaml", "images", "Images", listOf("e2e"))
    }

    @Test
    fun `checks workflow hosts the repo-wide gates`() {
        val checks = wf("checks.yaml")
        assertEquals("Checks", checks["name"].asText())
        val jobs = checks["jobs"]
        assertEquals("Test", jobs["test"]["name"].asText())
        assertEquals("Lint", jobs["lint"]["name"].asText())
        assertEquals("Qodana", jobs["qodana"]["name"].asText())
        val on = checks["on"] ?: checks["true"]
        assertTrue(on.has("pull_request"), "Checks must run on pull_request")
    }
}

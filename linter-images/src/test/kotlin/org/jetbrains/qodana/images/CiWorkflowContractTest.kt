package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `cli workflow builds and e2es the native binary`() {
        val cli = wf("cli.yaml")
        assertEquals("CLI", cli["name"].asText())
        val jobs = cli["jobs"]
        assertTrue(jobs.has("build") && !jobs.has("native-build"), "build job id must be 'build'")
        assertTrue(jobs.has("e2e") && !jobs.has("native-e2e"), "e2e job id must be 'e2e'")
        assertEquals("E2E qodana-cli (${'$'}{{ matrix.platform.name }})", jobs["e2e"]["name"].asText())
        // Every e2e platform's display `name` is slash-form (no dash outlier); the dash lives only in the
        // artifact/tag identifiers, which must stay dash to match native-build's upload-artifact names.
        jobs["e2e"]["strategy"]["matrix"]["platform"].forEach { p ->
            val pn = p["name"]?.asText() ?: error("e2e platform matrix entry missing a display 'name': $p")
            assertTrue(pn.contains("/") && !pn.contains("-"), "e2e platform display name must be slash-form: $pn")
        }
    }

    @Test
    fun `cli gate aggregates build and e2e`() {
        assertGate("cli.yaml", "cli", "CLI", listOf("build", "e2e"))
    }

    @Test
    fun `run steps have sentence-y names, never raw flags`() {
        // Scoped to the workflows this refactor cleans (the PR-gating set + pr-title). nightly/draft/publish
        // get light-touch (job-name-only) naming and drift is untouched/being retired — out of scope here.
        listOf("checks.yaml", "cli.yaml", "images.yaml", "pr-title.yaml").forEach { file ->
            wf(file)["jobs"].properties().forEach { (jobId, job) ->
                job["steps"]?.filter { it.has("run") }?.forEach { step ->
                    val name = step["name"]?.asText()
                    assertTrue(!name.isNullOrBlank(), "$file/$jobId: a run step has no name (shows the raw command)")
                    assertFalse(name!!.trim().startsWith("-"), "$file/$jobId step name is a raw flag: '$name'")
                    assertFalse(name.contains("--"), "$file/$jobId step name embeds a raw flag: '$name'")
                }
            }
        }
    }

    @Test
    fun `pr-title workflow uses Title Case names`() {
        val pr = wf("pr-title.yaml")
        assertEquals("PR Title", pr["name"].asText())
        assertEquals("Validate PR title", pr["jobs"]["validate"]["name"].asText())
    }

    @Test
    fun `both gates pin the identical alls-green ref`() {
        fun allsGreenRef(file: String, jobId: String) =
            wf(file)["jobs"][jobId]["steps"]
                .first { it["uses"]?.asText()?.startsWith("re-actors/alls-green") == true }["uses"].asText()
        assertEquals(
            allsGreenRef("images.yaml", "images"),
            allsGreenRef("cli.yaml", "cli"),
            "CLI and Images gates must pin the same re-actors/alls-green SHA",
        )
    }
}

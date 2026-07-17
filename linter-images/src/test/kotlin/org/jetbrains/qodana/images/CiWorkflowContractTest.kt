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
    private fun wf(file: String): JsonNode = YAMLMapper().readTree(Path.of("../.github/workflows/$file").readText())

    /** A gate job: aggregates a matrix/job set into one stable required check via re-actors/alls-green. */
    private fun assertGate(
        file: String,
        jobId: String,
        displayName: String,
        needs: List<String>,
    ) {
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
    fun `test job publishes the coverage XML artifact and Qodana does not consume it`() {
        val jobs = wf("checks.yaml")["jobs"]
        val testSteps = jobs["test"]["steps"]
        val testRuns = testSteps.mapNotNull { it["run"]?.asText() }

        // The test job builds the merged JaCoCo-compatible XML under -Pkover and publishes it as `coverage-xml`.
        assertTrue(
            testRuns.any { it.contains(":koverXmlReport") && it.contains("-Pkover") },
            "test job must run ./gradlew :koverXmlReport -Pkover",
        )
        val uploadWith =
            testSteps.single {
                it["uses"]?.asText()?.startsWith("actions/upload-artifact") == true &&
                    it["with"]?.get("name")?.asText() == "coverage-xml"
            }["with"]
        assertEquals("build/reports/kover/report.xml", uploadWith["path"].asText(), "upload the merged Kover XML")
        assertEquals("error", uploadWith["if-no-files-found"].asText(), "a missing report must fail the job loudly")

        // The dry-run guard keeps docker/native test tasks out of the coverage report. Assert it exists and
        // that its excluded set is EXACTLY disabledForTestTasks in kotlin-common — one source, no drift.
        val guardRun = testRuns.single { it.contains("--dry-run") }
        val grepExcluded =
            Regex(""":\(([^)]+)\)""")
                .find(guardRun)
                ?.groupValues
                ?.get(1)
                ?.split("|")
                ?.toSet()
        val conventionExcluded =
            Path
                .of("../build-logic/src/main/kotlin/kotlin-common.gradle.kts")
                .readText()
                .substringAfter("disabledForTestTasks.addAll(")
                .substringBefore(")")
                .let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        assertTrue(conventionExcluded.isNotEmpty(), "disabledForTestTasks must list the docker/native test tasks")
        assertEquals(conventionExcluded, grepExcluded, "CI guard grep must match disabledForTestTasks")

        // Coverage is published as an artifact only — Qodana must NOT be fed coverage (that coupling
        // bundled the % with per-method inspection warnings we didn't want).
        val qodanaSteps = jobs["qodana"]["steps"]
        assertTrue(
            qodanaSteps.none { it["uses"]?.asText()?.startsWith("actions/download-artifact") == true },
            "Qodana must not download coverage — it is published as an artifact, not consumed by Qodana",
        )
    }

    @Test
    fun `cli workflow builds and e2es the native binary`() {
        val cli = wf("cli.yaml")
        assertEquals("CLI", cli["name"].asText())
        val jobs = cli["jobs"]
        assertTrue(jobs.has("build") && !jobs.has("native-build"), "build job id must be 'build'")
        assertEquals(
            "Build ${'$'}{{ matrix.module }} (${'$'}{{ matrix.platform.name }})",
            jobs["build"]["name"].asText(),
            "build check name (symmetric with e2e; must keep module + slash platform)",
        )
        assertTrue(jobs.has("e2e") && !jobs.has("native-e2e"), "e2e job id must be 'e2e'")
        assertEquals("E2E qodana-cli (${'$'}{{ matrix.platform.name }})", jobs["e2e"]["name"].asText())
        // Every e2e platform's display `name` is slash-form (no dash outlier); the dash lives only in the
        // artifact/tag identifiers, which must stay dash to match the build job's upload-artifact names.
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
        // Scoped to the PR-gating workflows + pr-title; nightly/draft/publish/drift use job-name-only
        // naming and are out of scope.
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
        fun allsGreenRef(
            file: String,
            jobId: String,
        ) = wf(file)["jobs"][jobId]["steps"]
            .first { it["uses"]?.asText()?.startsWith("re-actors/alls-green") == true }["uses"]
            .asText()
        assertEquals(
            allsGreenRef("images.yaml", "images"),
            allsGreenRef("cli.yaml", "cli"),
            "CLI and Images gates must pin the same re-actors/alls-green SHA",
        )
    }

    // --- Expanded-name uniqueness guard (matrix cross-product) -----------------------------------------
    // `pre + listOf(it)`, NOT `pre + it`: a JsonNode is itself Iterable<JsonNode>, so `pre + it` would pick
    // Collection.plus(Iterable) and splice an object's field-values in instead of appending the node.
    private fun cartesian(lists: List<List<JsonNode>>): List<List<JsonNode>> =
        lists.fold(listOf<List<JsonNode>>(emptyList())) { acc, l -> acc.flatMap { pre -> l.map { pre + listOf(it) } } }

    private val matrixRef = Regex("""\$\{\{\s*matrix\.([\w.]+)\s*\}\}""")

    // The optional-suffix idiom the versioned e2e job name uses (QD-15369):
    //   ${{ matrix.<path> != '' && format('-{0}', matrix.<path>) || '' }}
    // → the value with a leading '-' when the axis is present, else empty. Resolve it BEFORE the plain
    // matrixRef pass so a versioned cell's name expands to the distinct string GitHub actually renders
    // (else all versions collapse to one literal name and the uniqueness guard mis-fires).
    private val optionalSuffixRef =
        Regex(
            """\$\{\{\s*matrix\.([\w.]+)\s*!=\s*''\s*&&\s*format\('-\{0}',\s*matrix\.[\w.]+\)\s*\|\|\s*''\s*\}\}""",
        )

    private fun resolvePath(
        path: String,
        binding: Map<String, JsonNode>,
    ): String? {
        val segs = path.split(".")
        var node: JsonNode? = binding[segs[0]]
        for (seg in segs.drop(1)) node = node?.get(seg)
        return node?.asText()
    }

    private fun substitute(
        template: String,
        binding: Map<String, JsonNode>,
    ): String {
        val withSuffix =
            optionalSuffixRef.replace(template) { m ->
                val v = resolvePath(m.groupValues[1], binding) ?: ""
                if (v.isNotEmpty()) "-$v" else ""
            }
        return matrixRef.replace(withSuffix) { m ->
            // Fail loud on an unresolved ref (a name pointing at a nonexistent matrix key) — never leave the
            // literal `${{ … }}` in, which would silently make cells look alike or unlike.
            resolvePath(m.groupValues[1], binding)
                ?: error("unresolved ${m.value} against matrix keys ${binding.keys} in '$template'")
        }
    }

    /** Every expanded job display name a workflow file produces (GitHub-style matrix cross-product). */
    private fun expandedNames(file: String): List<String> =
        wf(file)["jobs"]
            .properties()
            .asSequence()
            .flatMap { (id, job) ->
                val template = job["name"]?.asText() ?: id
                val matrix = job["strategy"]?.get("matrix") ?: return@flatMap sequenceOf(template)
                // include/exclude ADD/REMOVE combinations — not cross-product axes. Fail loud if a future matrix
                // uses them, so the guard is never silently wrong (rather than folding them into the product).
                check(!matrix.has("include") && !matrix.has("exclude")) {
                    "$file/$id matrix uses include/exclude — teach expandedNames to model them first"
                }
                val entries = matrix.properties().toList()
                val lists = entries.map { it.value.toList() }
                cartesian(lists).asSequence().map { combo ->
                    substitute(template, entries.map { it.key }.zip(combo).toMap())
                }
            }.toList()

    @Test
    fun `expanded check names are globally unique across PR-triggered workflows`() {
        val all = listOf("checks.yaml", "cli.yaml", "images.yaml", "pr-title.yaml").flatMap { expandedNames(it) }
        val dupes =
            all
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys
        assertTrue(dupes.isEmpty(), "duplicate expanded check names block required checks: $dupes")
    }
}

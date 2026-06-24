package org.jetbrains.qodana.images.e2e

import com.jetbrains.qodana.sarif.model.Result
import com.jetbrains.qodana.sarif.model.SarifReport
import org.jetbrains.qodana.images.process.ProcessCommandRunner
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.streams.asStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Docker-driving e2e suite. Tagged `linter-e2e` so it is excluded from the default `test` task and
 * runs only via `./gradlew :linter-images:linterE2eTest -Dlinter.e2e.image=<image>`.
 *
 * CI-ONLY: this docker-driving suite cannot run on the arm64 dev box. The arch-capable images
 * (`ArchContract.archCapable` — jvm, jvm-community, js, go, ruby/-3.2/-3.4) run arm64 e2e on
 * `ubuntu-24.04-arm`; the rest stay amd64-only pending QD-15171. Every `pin:"needs-pinning"` rule id
 * is resolved at runtime from the control variant's report (see [resolveVariantExpectations]); every
 * `[P]`-guessed `confirmed` id in the fixtures is pinned from a CI control run, never locally.
 *
 * Discovery: globs `e2e/fixtures/<linter.e2e.image>/<case>/expected.json` (cwd is the module root —
 * `linter-images/build.gradle.kts` pins the Test `workingDir`). One [DynamicTest] per
 * `(case, variant)`. With no `linter.e2e.image` set, [discover] yields an empty stream (asserted
 * locally by [LinterE2eDiscoveryTest]).
 */
@Tag("linter-e2e")
class LinterE2eTest {
    // CleanupMode.NEVER: the container runs as UID 1000 and writes files (e.g. .qodana/cache) into the
    // mounted project/results that the host CI runner cannot delete — JUnit's default cleanup then
    // throws DeletionException and reports a spurious `executionError`. CI runners are ephemeral (the
    // temp tree is discarded with the VM), so never deleting is correct here; the suite is CI-only.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var tempDir: Path

    private companion object {
        // Tail length for each diagnostic stream dumped on an exit-code mismatch.
        const val STREAM_TAIL = 3000
    }

    @TestFactory
    fun linterE2e(): Stream<DynamicNode> = discover()

    /**
     * The discovery itself, separated from the `@TestFactory` method so [LinterE2eDiscoveryTest]
     * can invoke it directly (no JUnit engine) to assert the empty-when-unset contract.
     */
    fun discover(): Stream<DynamicNode> {
        // No image selected → empty (the legit `./gradlew :linter-images:test` path; asserted by
        // LinterE2eDiscoveryTest). A SELECTED image that resolves to no fixtures is a misconfiguration,
        // so it fails LOUDLY rather than silently passing zero tests (CLAUDE.md no-silent-skip).
        val image = System.getProperty("linter.e2e.image") ?: return Stream.empty()
        val imageRoot = Path.of("e2e", "fixtures", image)
        val imageTag = "$image:dev"
        val nodes: List<DynamicNode> =
            when {
                !Files.isDirectory(imageRoot) ->
                    failingNodes(image, "no fixtures directory (expected $imageRoot)")
                else -> {
                    val cases =
                        imageRoot
                            .listDirectoryEntries()
                            .filter { Files.isDirectory(it) && it.resolve("expected.json").isRegularFile() }
                            .sortedBy { it.name }
                    if (cases.isEmpty()) {
                        failingNodes(image, "no fixture cases ($imageRoot/*/expected.json)")
                    } else {
                        cases.flatMap { caseDir -> casesFor(caseDir, imageTag) }
                    }
                }
            }
        return nodes.asSequence().asStream()
    }

    private fun failingNodes(
        image: String,
        why: String,
    ): List<DynamicNode> =
        listOf(
            DynamicTest.dynamicTest("linter-e2e[$image]") {
                fail("-Dlinter.e2e.image=$image but $why")
            },
        )

    private fun casesFor(
        caseDir: Path,
        imageTag: String,
    ): List<DynamicNode> {
        val manifest = ManifestLoader.load(caseDir.resolve("expected.json"))
        return manifest.run.variants.map { variant ->
            DynamicTest.dynamicTest("${manifest.case} [${variant.id}]") {
                runVariant(manifest, caseDir, variant, imageTag)
            }
        }
    }

    private fun runVariant(
        manifest: ExpectedManifest,
        caseDir: Path,
        variant: Variant,
        imageTag: String,
    ) {
        requireDocker()

        val runner = LinterE2eCaseRunner(ProcessCommandRunner(), tempDir)
        val result = runner.run(manifest, caseDir, variant, imageTag)

        assertEquals(
            manifest.expectExitCode,
            result.command.exitCode,
            "${manifest.case} [${variant.id}] exit code mismatch. The scan's own error often lands in " +
                "idea.log, not stderr, so all three streams are dumped:\n" +
                "stderr:\n${result.command.stderr.takeLast(STREAM_TAIL)}\n" +
                "stdout:\n${result.command.stdout.takeLast(STREAM_TAIL)}\n" +
                "idea.log tail:\n${result.ideaLog?.takeLast(STREAM_TAIL) ?: "<no idea.log produced>"}",
        )

        // SARIF is only required when the manifest actually asserts on it. The android
        // missing-SDK negative twin (QD-2179) deliberately fails the scan: non-zero exit,
        // no/partial SARIF, and its signal lives entirely in `log.mustContain`. Hard-failing
        // on a missing report there would mask the real assertion.
        val sarifAsserted =
            manifest.sarif.expectations.isNotEmpty() ||
                manifest.sarif.tool != null ||
                manifest.sarif.qodanaSeverityRequired
        if (sarifAsserted) {
            val report =
                result.report
                    ?: fail(
                        result.sarifParseError?.let {
                            "${manifest.case} [${variant.id}] qodana.sarif.json exists but FAILED TO PARSE " +
                                "(this case asserts on SARIF, so a parse failure is fatal, not a skip): $it"
                        } ?: (
                            "${manifest.case} [${variant.id}] produced no qodana.sarif.json; results dir contents:\n" +
                                listing(result.resultsDir)
                        ),
                    )
            val sarif = resolveVariantExpectations(manifest, caseDir, variant, imageTag)
            val violations = SarifExpectationEvaluator().evaluate(report, sarif)
            assertTrue(
                violations.isEmpty(),
                "${manifest.case} [${variant.id}] SARIF expectations failed:\n" +
                    violations.joinToString("\n") { "  - ${it.reason}: ${it.detail}" } +
                    "\n" + reportInventory(report),
            )
        }

        val ideaLog = result.ideaLog
        // A null idea.log trivially satisfies mustNotContain; it FAILS mustContain.
        manifest.log.mustNotContain.forEach { rule ->
            assertTrue(
                ideaLog == null || !ideaLog.contains(rule.text),
                "${manifest.case} [${variant.id}] idea.log must not contain '${rule.text}' (guards ${rule.guards})",
            )
        }
        manifest.log.mustContain.forEach { rule ->
            assertTrue(
                ideaLog != null && ideaLog.contains(rule.text),
                "${manifest.case} [${variant.id}] idea.log must contain '${rule.text}' (guards ${rule.guards}); " +
                    if (ideaLog == null) "idea.log was not produced" else "idea.log tail:\n${ideaLog.takeLast(2000)}",
            )
        }
    }

    /**
     * The [SarifExpectations] to evaluate against THIS variant's report. Two jobs:
     *
     *  1. **Scope** to expectations applicable to this variant (`variant == null` ⇒ all variants,
     *     else the named one). Essential for control/treatment cases: a `control`-only "present"
     *     expectation must NOT be checked against the `treatment` report (where the rule was excluded
     *     and is legitimately absent). The evaluator itself is variant-agnostic, so scoping happens
     *     here.
     *  2. **Dynamically pin** the applicable `absent` + `needs-pinning` expectations: their rule id is
     *     whatever the rule fired as in the CONTROL variant. We run the control variant ONCE, find the
     *     control result matching the expectation's `uriContains`/`messageContains`, and rewrite the
     *     expectation to a `confirmed` exact-id check. Used by `jvm-exclude-control`: "rule X present
     *     in control under planted/, ABSENT in treatment." Control's own `present` expectations are
     *     NEVER auto-resolved — they keep their literal (manually CI-pinned) id; resolving them from
     *     the treatment run would find nothing and wrongly fail.
     *
     * The "control" variant is the first variant whose id != this variant's id.
     */
    private fun resolveVariantExpectations(
        manifest: ExpectedManifest,
        caseDir: Path,
        variant: Variant,
        imageTag: String,
    ): SarifExpectations {
        val base = manifest.sarif
        val applicable = base.expectations.filter { it.variant == null || it.variant == variant.id }
        val toPin = applicable.filter { it.pin == "needs-pinning" && it.presence == "absent" }
        // A needs-pinning absent expectation resolves its rule id by matching a control result on
        // uriContains/messageContains. Without a selector, matches(..., null, null) returns the FIRST
        // control result arbitrarily — a silent mis-pin. Require a selector and fail loudly instead.
        toPin.forEach {
            check(it.uriContains != null || it.messageContains != null) {
                "${manifest.case} [${variant.id}]: a needs-pinning absent expectation must carry a " +
                    "uriContains or messageContains selector to resolve its rule id from the control run"
            }
        }
        if (toPin.isEmpty()) return base.copy(expectations = applicable)

        val controlVariant =
            manifest.run.variants.firstOrNull { it.id != variant.id }
                ?: error(
                    "${manifest.case} [${variant.id}] has an absent needs-pinning expectation " +
                        "but no control variant to resolve its rule id from",
                )
        val controlReport =
            LinterE2eCaseRunner(ProcessCommandRunner(), tempDir)
                .run(manifest, caseDir, controlVariant, imageTag)
                .report
                ?: fail("${manifest.case}: control variant '${controlVariant.id}' produced no SARIF for pinning")

        val resolved =
            applicable.map { exp ->
                if (exp !in toPin) {
                    exp
                } else {
                    val ruleId =
                        controlReport
                            .resultsFlat()
                            .firstOrNull { matches(it, exp.uriContains, exp.messageContains) }
                            ?.ruleId
                            ?: fail(
                                "${manifest.case}: could not resolve needs-pinning ruleId from control " +
                                    "'${controlVariant.id}' (uriContains=${exp.uriContains}, " +
                                    "messageContains=${exp.messageContains})",
                            )
                    exp.copy(ruleId = ruleId, ruleIdPattern = null, pin = "confirmed")
                }
            }
        return base.copy(expectations = resolved)
    }

    private fun matches(
        result: Result,
        uriContains: String?,
        messageContains: String?,
    ): Boolean {
        val uriOk =
            uriContains == null ||
                (
                    result.locations
                        ?.firstOrNull()
                        ?.physicalLocation
                        ?.artifactLocation
                        ?.uri
                        ?.contains(uriContains) == true
                )
        val msgOk =
            messageContains == null ||
                (result.message?.text?.contains(messageContains) == true)
        return uriOk && msgOk
    }

    // On a SARIF-expectation failure, dump what the scan ACTUALLY produced — the driver identity and
    // the rule-id histogram — so `needs-pinning` ids (and the per-image driver name) can be read
    // straight from the CI job log and committed, with no artifact download.
    private fun reportInventory(report: SarifReport): String {
        val driver =
            report.runs
                .orEmpty()
                .firstOrNull()
                ?.tool
                ?.driver
        val results = report.runs.orEmpty().flatMap { it.results.orEmpty() }
        val histogram =
            results
                .groupingBy { it.ruleId ?: "<null-ruleId>" }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .joinToString("\n") { "      ${it.value}x ${it.key}" }
                .ifEmpty { "      <none>" }
        return "    observed driver: name=${driver?.name} fullName=${driver?.fullName}\n" +
            "    observed ruleIds (${results.size} results):\n" + histogram
    }

    private fun requireDocker() {
        val info = ProcessCommandRunner().run(listOf("docker", "info"))
        if (!info.isSuccess) {
            fail("@Tag(\"linter-e2e\") test ran but Docker is unreachable: ${info.stderr.ifBlank { info.stdout }}")
        }
    }

    private fun listing(dir: Path): String =
        runCatching {
            Files.walk(dir).use { stream ->
                stream
                    .map { dir.relativize(it).toString() }
                    .filter { it.isNotEmpty() }
                    .sorted()
                    .toList()
                    .joinToString("\n") { "    $it" }
            }
        }.getOrElse { "    <unable to list $dir: ${it.message}>" }
}

private fun SarifReport.resultsFlat(): List<Result> = runs.orEmpty().flatMap { it.results.orEmpty() }

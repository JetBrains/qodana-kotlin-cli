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
 * Contract invariants for draft-release.yaml's native-packaging matrices, guarding two regressions that
 * only surface in the release path (never on PR/push CI): a packaging platform diverging from cli.yaml's
 * build matrix, and an optional gradle-args array expanded without a nounset guard (aborts on macOS bash
 * 3.2). The nounset check is structural — the bash-3.2 failure mode can't be reproduced on the Linux test
 * runner (bash 5 tolerates empty-array expansion even under BASH_COMPAT); the behavioral guard is the
 * nightly's own macOS packaging run.
 */
class DraftReleaseWorkflowContractTest {
    private fun wf(file: String): JsonNode = YAMLMapper().readTree(Path.of("../.github/workflows/$file").readText())

    private fun buildPlatforms(
        file: String,
        jobId: String,
    ): Set<String> = wf(file)["jobs"][jobId]["strategy"]["matrix"]["platform"].map { it["name"].asText() }.toSet()

    private fun packagingRun(jobId: String): String =
        wf("draft-release.yaml")["jobs"][jobId]["steps"]
            .first { it["run"]?.asText()?.contains("assembleRelease") == true }["run"]
            .asText()

    private fun verifyAssetSetRun(): String =
        wf("draft-release.yaml")["jobs"]["assemble"]["steps"]
            .first { it["name"]?.asText() == "Verify asset set" }["run"]
            .asText()

    @Test
    fun `packaging matrices match the CLI build platforms (no darwin-amd64)`() {
        val cliPlatforms = buildPlatforms("cli.yaml", "build")
        assertTrue("darwin/amd64" !in cliPlatforms, "precondition: cli.yaml already excludes darwin/amd64")
        listOf("build-cli", "build-clang-cdnet").forEach { jobId ->
            assertEquals(
                cliPlatforms,
                buildPlatforms("draft-release.yaml", jobId),
                "$jobId platforms must match cli.yaml build (no GraalVM CE macOS x64 at 25.0.2)",
            )
        }
    }

    @Test
    fun `assemble asset set is darwin-arm64-only (no Intel-Mac binaries)`() {
        val verify = verifyAssetSetRun()
        // Intel-Mac tokens gone...
        listOf("darwin_x86_64", "darwin_amd64").forEach { token ->
            assertFalse(verify.contains(token), "assemble EXPECTED_FILES still lists Intel-Mac token '$token'")
        }
        // ...but the darwin/arm64 assets an over-deletion could wrongly drop are still expected.
        listOf(
            "qodana_darwin_arm64.tar.gz",
            "qodana-clang_\${VERSION}_darwin_arm64",
            "qodana-cdnet_\${VERSION}_darwin_arm64",
        ).forEach { token ->
            assertTrue(verify.contains(token), "assemble EXPECTED_FILES dropped the darwin/arm64 asset '$token'")
        }
    }

    @Test
    fun `packaging steps expand the optional gradle-args array with the nounset-safe idiom`() {
        listOf("build-cli", "build-clang-cdnet").forEach { jobId ->
            val run = packagingRun(jobId)
            assertTrue(run.contains("set -euo pipefail"), "$jobId packaging step must run under nounset")
            assertTrue(run.contains("EXTRA=()"), "$jobId packaging step builds the EXTRA args array")
            // A bare "${'$'}{EXTRA[@]}" aborts under set -u on macOS bash 3.2; the full empty-safe idiom
            // expands to nothing when EXTRA is empty. Assert the EXACT idiom, not just a prefix.
            assertTrue(
                run.contains("\"\${EXTRA[@]+\"\${EXTRA[@]}\"}\""),
                "$jobId must expand EXTRA via the exact idiom \"\${EXTRA[@]+\"\${EXTRA[@]}\"}\"",
            )
        }
    }
}

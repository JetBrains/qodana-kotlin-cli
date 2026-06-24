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
 * Locks in the "token-gated images fail loudly, not silently" invariant for the linter-images CI
 * matrix: a token-gated cell (clang/cdnet) with an empty QODANA_READ_SPACE_PACKAGES_TOKEN must fail
 * RED (a gate step that `exit 1`s), never no-op to green. Mirrors the feed_required gate. Test CWD is
 * the module root, so the repo-root workflow is read via `../` (cf. EslintPinTest).
 */
class LinterImagesWorkflowContractTest {
    private val workflow: JsonNode =
        YAMLMapper().readTree(Path.of("../.github/workflows/linter-images.yaml").readText())

    private val steps: List<JsonNode>
        get() = workflow["jobs"]["e2e"]["steps"].toList()

    private val cells: List<JsonNode>
        get() = workflow["jobs"]["e2e"]["strategy"]["matrix"]["image"].toList()

    private fun JsonNode.ifExpr(): String = this["if"]?.asText() ?: ""

    private fun JsonNode.runScript(): String = this["run"]?.asText() ?: ""

    /** Steps whose `if` contains every given expression fragment. Keeps the filter lambdas ≤120 cols. */
    private fun stepsMatching(vararg fragments: String): List<JsonNode> =
        steps.filter { step -> fragments.all { step.ifExpr().contains(it) } }

    private val tokenGated = "matrix.image.token_gated == 'true'"
    private val feedRequired = "matrix.image.feed_required == 'true'"
    private val emptyToken = "env.QODANA_READ_SPACE_PACKAGES_TOKEN == ''"
    private val nonEmptyToken = "env.QODANA_READ_SPACE_PACKAGES_TOKEN != ''"

    @Test
    fun `token-gated cells exist (clang and cdnet)`() {
        val names =
            cells
                .filter { it["token_gated"]?.asText() == "true" }
                .map { it["name"].asText() }
        assertTrue(names.containsAll(listOf("qodana-clang", "qodana-cdnet")), "token-gated cells: $names")
    }

    @Test
    fun `empty-token token-gated step is a single loud hard-fail`() {
        val gate = stepsMatching(tokenGated, emptyToken)
        assertEquals(1, gate.size, "expected exactly one token-gated empty-token step (the gate)")
        assertTrue(gate.single().runScript().contains("exit 1"), "the gate must exit 1, not no-op")
    }

    @Test
    fun `no token-gated step is guarded by a non-empty-token check`() {
        val guarded = stepsMatching(tokenGated, nonEmptyToken)
        assertTrue(guarded.isEmpty(), "token-gated build/e2e steps must not re-guard on token != '': $guarded")
    }

    @Test
    fun `feed_required gate remains a loud hard-fail (regression guard)`() {
        val gate = stepsMatching(feedRequired, emptyToken)
        assertEquals(1, gate.size, "expected exactly one feed_required empty-token gate")
        assertTrue(gate.single().runScript().contains("exit 1"))
    }

    @Test
    fun `release-smoke builds qodana-jvm via the release overlay, fork-gated not token-gated, no Space token`() {
        val job = workflow["jobs"]["release-smoke"]
        assertTrue(job != null, "linter-images.yaml must define a release-smoke job")
        val buildStep =
            job!!["steps"].toList().single {
                it.runScript().contains("compose.release.yaml") && it.runScript().contains("build qodana-jvm")
            }
        // Must NOT gate the build on the registry token — that would silently skip to GREEN on a same-repo
        // misconfig (the e2e job fails loudly instead). Fork PRs are excluded via the fork signal.
        assertTrue(
            "DOCKER_READ_PUBLIC_REGISTRY_TOKEN" !in buildStep.ifExpr(),
            "release-smoke build must not gate on the registry token; gate forks via head.repo.fork",
        )
        assertTrue("fork" in buildStep.ifExpr(), "release-smoke build must be fork-gated (head.repo.fork)")
        assertTrue(
            job["env"]?.get("QODANA_READ_SPACE_PACKAGES_TOKEN") == null,
            "release-smoke must NOT carry the Space token, so a silently-unapplied overlay fails RED on sha256",
        )
    }

    @Test
    fun `the drift canary re-verifies the release pins against the public feed`() {
        val drift = YAMLMapper().readTree(Path.of("../.github/workflows/linter-images-drift.yaml").readText())
        val canaryStep = drift["jobs"]["canary"]["steps"].toList().single { it.runScript().contains("verify-pin") }
        val script = canaryStep.runScript()
        // Bind to the release loop specifically: the phase-0 _RELEASE key derivation AND a verify-pin call
        // against the release feed var. Deleting the release verify-pin block reddens this (not just a stray comment).
        assertTrue("_RELEASE" in script, "the canary must derive the QODANA_<X>_RELEASE_* key")
        assertTrue(
            "--distribution-feed \"\$PUBLIC\"" in script,
            "the canary must verify-pin the release pins against the public feed",
        )
    }

    @Test
    fun `every e2e cell declares arch and runner`() {
        cells.forEach { c ->
            val n = c["name"].asText()
            assertTrue(c["arch"]?.asText() in setOf("amd64", "arm64"), "$n: arch must be amd64|arm64")
            assertTrue(!c["runner"]?.asText().isNullOrBlank(), "$n: runner must be set")
        }
    }

    @Test
    fun `qodana-jvm has exactly one amd64 cell and one arm64 cell`() {
        val jvm = cells.filter { it["name"].asText() == "qodana-jvm" }
        assertEquals(setOf("amd64", "arm64"), jvm.map { it["arch"].asText() }.toSet(), "jvm cells: amd64 + arm64")
        assertEquals(2, jvm.size, "exactly two qodana-jvm cells")
        val arm = jvm.single { it["arch"].asText() == "arm64" }
        assertEquals("ubuntu-24.04-arm", arm["runner"].asText(), "arm64 jvm cell runs on an arm64 runner")
        // INVARIANT guard: arm64 cells run only on arm64 runners (-PtargetArch is a label, not cross-compile).
        cells.filter { it["arch"]?.asText() == "arm64" }.forEach {
            val n = it["name"].asText()
            assertTrue(it["runner"].asText().endsWith("-arm"), "$n: arm64 cell must use an -arm runner")
        }
    }

    @Test
    fun `e2e job name is platform-tagged and runner-independent`() {
        val name = workflow["jobs"]["e2e"]["name"].asText()
        assertTrue(name.contains("linux/\${{ matrix.image.arch }}"), "check name must carry linux/<arch>, got: $name")
        assertFalse(name.contains("ubuntu"), "check name must not embed the runner id: $name")
    }
}

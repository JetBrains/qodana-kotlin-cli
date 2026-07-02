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
    private val notTokenGated = "matrix.image.token_gated != 'true'"
    private val requiresLicense = "matrix.image.requires_license == 'true'"
    private val emptyLicense = "env.QODANA_LICENSE_ONLY_TOKEN == ''"
    private val nonFork = "head.repo.fork != true"

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
    fun `licensed cells hard-fail on an empty license token for non-fork runs`() {
        // Domain matches the licensed-e2e step it guards (token_gated != 'true').
        val gate = stepsMatching(notTokenGated, requiresLicense, nonFork, emptyLicense)
        assertEquals(1, gate.size, "expected exactly one licensed empty-token non-fork gate")
        assertTrue(gate.single().runScript().contains("exit 1"), "the licensed gate must exit 1, not no-op")
        assertTrue(gate.single().runScript().contains("::error::"), "the licensed gate must emit a loud ::error::")
    }

    @Test
    fun `a non-fork empty license token cannot reach a scan-skip path`() {
        val gateIdx =
            steps.indexOfFirst { s ->
                listOf(notTokenGated, requiresLicense, nonFork, emptyLicense).all { s.ifExpr().contains(it) }
            }
        val licensedE2eIdx = steps.indexOfFirst { it.ifExpr().contains("env.QODANA_LICENSE_ONLY_TOKEN != ''") }
        val noteIdx = steps.indexOfFirst { it.runScript().contains("QODANA_LICENSE_ONLY_TOKEN unavailable") }
        assertTrue(gateIdx in 0 until licensedE2eIdx, "gate must precede licensed e2e ($gateIdx<$licensedE2eIdx)")
        assertTrue(gateIdx < noteIdx, "gate must precede note-skipped ($gateIdx<$noteIdx)")
        assertTrue(steps[gateIdx]["continue-on-error"]?.asBoolean() != true, "the gate must not continue-on-error")
        assertTrue(steps[noteIdx].ifExpr().contains("head.repo.fork == true"), "note-skipped must be fork-scoped")
        // Pin the e2e step's guard domain to the gate's, so a drift in EITHER guard reddens rather than
        // silently reopening the soft-skip hole.
        val e2eIf = steps[licensedE2eIdx].ifExpr()
        assertTrue(
            e2eIf.contains(notTokenGated) && e2eIf.contains(requiresLicense),
            "licensed e2e step must share the gate's domain (requires_license && token_gated != 'true'): $e2eIf",
        )
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
    fun `exactly the arch-capable images have an amd64 and arm64 cell`() {
        val arm64Images = cells.filter { it["arch"]?.asText() == "arm64" }.map { it["name"].asText() }.toSet()
        assertEquals(ArchContract.archCapable, arm64Images, "exactly the arch-capable images may have an arm64 cell")
        for (img in ArchContract.archCapable) {
            val arches = cells.filter { it["name"].asText() == img }.map { it["arch"].asText() }
            assertEquals(listOf("amd64", "arm64").sorted(), arches.sorted(), "$img needs one amd64 + one arm64 cell")
        }
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

    @Test
    fun `drift canary ARM64_SLUGS equals the arch-capable images' dist slugs`() {
        // The drift canary's ARM64_SLUGS is a 4th copy of the arch-capable set (keyed by dist slug, so
        // ruby-3.2/-3.4 collapse to qodana-ruby). Tie it to the single source so a forgotten update reddens
        // here, not silently dropping an image's arm64 .sha256 re-verification.
        val drift = Path.of("../.github/workflows/linter-images-drift.yaml").readText()
        val declared =
            Regex("""ARM64_SLUGS=\(([^)]*)\)""")
                .find(drift)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.toSet()
                ?: error("ARM64_SLUGS=(...) not found in linter-images-drift.yaml")
        val expected = ArchContract.archCapable.map { EnvContract.parseEnv(it).getValue("QD_LINTER_SLUG") }.toSet()
        assertEquals(expected, declared, "drift ARM64_SLUGS must equal arch-capable images' dist slugs")
    }

    @Test
    fun `each arch-capable image's arm64 cell has the same gating keys and values as its amd64 cell`() {
        for (img in ArchContract.archCapable) {
            val byArch = cells.filter { it["name"].asText() == img }.associateBy { it["arch"].asText() }
            val amd64 = byArch.getValue("amd64")
            val arm64 = byArch.getValue("arm64")
            // Full key-set equality first: catches a NEW gating dimension added to only one cell (a hand-listed
            // subset would miss it). arch/runner keys are present on both — only their VALUES legitimately differ.
            assertEquals(
                amd64.fieldNames().asSequence().toSet(),
                arm64.fieldNames().asSequence().toSet(),
                "$img: arm64 cell must declare exactly the same keys as amd64",
            )
            // arch/runner differ by design; every other key's value must match.
            amd64.fieldNames().asSequence().filter { it !in setOf("arch", "runner") }.forEach { k ->
                assertEquals(amd64[k].asText(), arm64[k].asText(), "$img '$k': arm64 must match amd64")
            }
        }
    }

    @Test
    fun `qodana-dotnet is inside the licensed hard-fail gate's domain`() {
        // Parity above only pins arm64 == amd64. Also pin the ABSOLUTE values, so a symmetric flip of both
        // cells to requires_license:"false" (which parity would accept) can't silently drop dotnet out of the
        // license gate + licensed-e2e path and green with no scan.
        val dotnet = cells.filter { it["name"].asText() == "qodana-dotnet" }
        assertEquals(2, dotnet.size, "qodana-dotnet needs an amd64 + an arm64 cell")
        dotnet.forEach {
            val arch = it["arch"].asText()
            assertEquals(
                "true",
                it["requires_license"]?.asText(),
                "qodana-dotnet/$arch must be requires_license (gate + licensed-e2e domain)",
            )
            assertEquals("false", it["token_gated"]?.asText(), "qodana-dotnet/$arch must not be token_gated")
        }
    }

    @Test
    fun `each cell's feed wiring matches its dot-env feed state`() {
        // Bind the workflow matrix to the .env source of truth: an image whose .env is on the internal
        // nightly feed MUST carry feed_required + the compose.private.yaml overlay (which mounts the Space
        // token for the sha256 dist fetch). The converse of compose.private does NOT hold — token-gated
        // clang/cdnet also mount it for the mirror token — so the inverse only pins feed_required. Catches a
        // cell's feed wiring drifting from its .env in a unit test, not only at CI build time.
        val internalFeedUrl = "https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed"
        cells.forEach { cell ->
            val name = cell["name"].asText()
            val arch = cell["arch"].asText()
            val onInternalFeed = EnvContract.parseEnv(name)["QD_DISTRIBUTION_FEED"] == internalFeedUrl
            val feedRequiredVal = cell["feed_required"]?.asText()
            val composeFiles = cell["compose_files"].asText()
            if (onInternalFeed) {
                assertEquals("true", feedRequiredVal, "$name/$arch on the internal feed → feed_required must be true")
                assertTrue(
                    "compose.private.yaml" in composeFiles,
                    "$name/$arch on the internal feed → compose_files must include compose.private.yaml: $composeFiles",
                )
            } else {
                assertFalse(
                    feedRequiredVal == "true",
                    "$name/$arch has no embedded dist → feed_required must not be true (was $feedRequiredVal)",
                )
            }
        }
    }
}

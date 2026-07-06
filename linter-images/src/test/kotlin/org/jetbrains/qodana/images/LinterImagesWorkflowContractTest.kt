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

    /** e2e harness steps (`linterE2eTest`) whose `if:` carries the given `requires_license` domain fragment. */
    private fun harnessSteps(domain: String): List<JsonNode> =
        steps.filter { it.runScript().contains("linterE2eTest") && it.ifExpr().contains(domain) }

    private val licensedImageNames: Set<String>
        get() = cells.filter { it["requires_license"]?.asText() == "true" }.map { it["name"].asText() }.toSet()

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
    fun `the licensed and free e2e steps pin their exact domains (no per-cell skip conjunct)`() {
        // NO SILENT SKIPS, 100% of cells: pin each e2e step's if: to EXACTLY its two-term domain, so a regression
        // appending a conjunct (a re-added `&& … != ''` skip guard, a fork gate, or `&& matrix.image.name != 'x'`)
        // that silently skips a cell reddens here. The scan RUNS on every paid cell and fails loud on a missing token.
        val licensed = harnessSteps("requires_license == 'true'")
        assertEquals(1, licensed.size, "one licensed e2e step, got ${licensed.map { it["name"]?.asText() }}")
        assertEquals(
            "\${{ matrix.image.token_gated != 'true' && matrix.image.requires_license == 'true' }}",
            licensed.single().ifExpr(),
            "licensed e2e step must be exactly its two-term domain — no skip guard, fork gate, or extra conjunct",
        )
        val free = harnessSteps("requires_license != 'true'")
        assertEquals(1, free.size, "one free e2e step, got ${free.map { it["name"]?.asText() }}")
        assertEquals(
            "\${{ matrix.image.token_gated != 'true' && matrix.image.requires_license != 'true' }}",
            free.single().ifExpr(),
            "free e2e step must be exactly its complementary two-term domain",
        )
    }

    @Test
    fun `every requires_license fixture passes the license token, so an unlicensed scan fails loud`() {
        // Deleting the "Require license token" gate is safe ONLY because a paid scan fails loud on a missing token:
        // the fixture lists QODANA_LICENSE_ONLY_TOKEN in passEnv, so the harness error()s on a blank one. Lock the
        // wiring for EVERY paid cell, else a future paid image whose fixture omits the token scans unlicensed.
        assertTrue(licensedImageNames.isNotEmpty(), "sanity: there are requires_license cells")
        licensedImageNames.forEach { image ->
            val dirs =
                Path
                    .of("e2e/fixtures/$image")
                    .toFile()
                    .listFiles()
                    ?.filter { it.isDirectory && it.resolve("expected.json").isFile }
                    .orEmpty()
            assertTrue(dirs.isNotEmpty(), "$image is requires_license but has no e2e fixture (no fail-loud target)")
            dirs.forEach { dir ->
                val passEnv =
                    YAMLMapper()
                        .readTree(dir.resolve("expected.json").readText())["run"]
                        ?.get("passEnv")
                        ?.map { it.asText() }
                        .orEmpty()
                assertTrue(
                    "QODANA_LICENSE_ONLY_TOKEN" in passEnv,
                    "$image/${dir.name}: fixture must pass QODANA_LICENSE_ONLY_TOKEN via passEnv",
                )
            }
        }
    }

    @Test
    fun `the license-token gate and its fork note-skip are gone`() {
        // Both were band-aids over the deleted skip guard; the scan step now fails loud on its own.
        val gate = steps.filter { it.ifExpr().contains("QODANA_LICENSE_ONLY_TOKEN == ''") }
        assertTrue(gate.isEmpty(), "no 'Require license token' gate may remain: ${gate.map { it["name"]?.asText() }}")
        val note = steps.filter { it.runScript().contains("QODANA_LICENSE_ONLY_TOKEN unavailable") }
        assertTrue(note.isEmpty(), "no fork note-skip may remain: ${note.map { it["name"]?.asText() }}")
    }

    @Test
    fun `no e2e-job step is fork-gated`() {
        // The e2e fork sites (the deleted gate + note-skip) are gone. The file-wide raw-text lock lands in the
        // release-smoke test, once release-smoke is scrubbed too.
        val forkGated = steps.filter { it.ifExpr().contains("head.repo.fork") }
        assertTrue(forkGated.isEmpty(), "no e2e step may be fork-gated: ${forkGated.map { it["name"]?.asText() }}")
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
        // Parity above only pins arm64 == amd64. Also pin the ABSOLUTE values: a symmetric flip of both cells to
        // requires_license:"false" (which parity would accept) would silently drop dotnet out of the (now
        // unconditional) licensed-e2e path into the free path, scanning it unlicensed. (There is no longer a
        // "license gate" — the scan step itself fails loud on a missing token; this pins dotnet stays in its domain.)
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

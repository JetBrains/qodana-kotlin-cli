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
        YAMLMapper().readTree(Path.of("../.github/workflows/images.yaml").readText())

    // Jackson/SnakeYAML may resolve the `on:` mapping key as a YAML 1.1 boolean (→ field name "true"); try both.
    private val onTriggers: JsonNode
        get() = workflow["on"] ?: workflow["true"] ?: error("`on:` trigger block not found in images.yaml")

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
        // A paid scan fails loud on a missing token ONLY if the fixture lists QODANA_LICENSE_ONLY_TOKEN in passEnv
        // (the harness error()s on a blank one). Lock the wiring for EVERY paid cell, else a paid image whose
        // fixture omits the token scans unlicensed.
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
        // The scan step fails loud on its own; neither gate nor fork note-skip may remain.
        val gate = steps.filter { it.ifExpr().contains("QODANA_LICENSE_ONLY_TOKEN == ''") }
        assertTrue(gate.isEmpty(), "no 'Require license token' gate may remain: ${gate.map { it["name"]?.asText() }}")
        val note = steps.filter { it.runScript().contains("QODANA_LICENSE_ONLY_TOKEN unavailable") }
        assertTrue(note.isEmpty(), "no fork note-skip may remain: ${note.map { it["name"]?.asText() }}")
    }

    @Test
    fun `release-smoke runs the build unconditionally, no Space token`() {
        val job = workflow["jobs"]["release-smoke"]
        assertTrue(job != null, "images.yaml must define a release-smoke job")
        // Anchor the build on the release overlay (its defining, stable signal), not literal command fragments.
        val buildStep = job!!["steps"].toList().single { it.runScript().contains("compose.release.yaml") }
        // The build runs unconditionally (a missing cred reds it at login/base-pull). Its overlay dist flip stays
        // guarded by the no-Space-token tripwire below. We test that the image builds, not creds.
        assertEquals("", buildStep.ifExpr(), "the release build must be unconditional so it always runs")
        // NO SILENT SKIPS across the whole job: every step runs unconditionally, so no step
        // (login/checkout/stage/build) may carry a skip conjunct — a fork gate or an `env.X != ''` guard.
        job["steps"].toList().forEach { s ->
            val n = s["name"]?.asText()
            assertFalse(s.ifExpr().contains("''"), "$n: no release-smoke step may skip-guard on an empty secret")
            assertFalse(s.ifExpr().contains("head.repo.fork"), "$n: no release-smoke step may be fork-gated")
        }
        assertTrue(
            job["env"]?.get("QODANA_READ_SPACE_PACKAGES_TOKEN") == null,
            "release-smoke must NOT carry the Space token, so a silently-unapplied overlay fails RED on sha256",
        )
    }

    @Test
    fun `the workflow has no fork-signal gating anywhere`() {
        // Raw-text lock over the whole file — catches a fork signal a structural job/step-`if:` scan would miss
        // (a matrix exclude, continue-on-error, runs-on, with:/env: interpolation, or a comment). It is a
        // BLOCKLIST of the realistic fork/actor discriminators, NOT an exhaustive proof: a positive same-repo gate
        // (`github.repository == '<literal>'`) is the known residual.
        val text = Path.of("../.github/workflows/images.yaml").readText()
        val forkDiscriminators =
            listOf(
                "head.repo.fork", // canonical fork spelling
                "head.repo.full_name", // repo-name comparison (`!= github.repository`)
                "head.repo.owner", // owner-login comparison
                "head.repo.id", // repo-id comparison
                "author_association", // PR-author / collaborator allow-list
                "github.actor", // actor allow-list (maintainer-only gating)
                "triggering_actor", // github.triggering_actor allow-list
                "pull_request_target", // the trigger that would hand forks secrets
            )
        forkDiscriminators.forEach { spelling ->
            assertFalse(text.contains(spelling), "'$spelling' fork/actor gating is banned (no silent fork skips)")
        }
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
        assertTrue(name.startsWith("E2E "), "e2e check must use the unified 'E2E <subject> …' shape, got: $name")
        assertTrue(name.contains("\${{ matrix.image.name }}"), "e2e check must keep the per-cell image subject: $name")
        assertFalse(name.contains("Docker"), "drop the redundant 'Docker' prefix from the e2e check: $name")
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
        // requires_license:"false" (which parity would accept) would silently drop dotnet out of the licensed-e2e
        // path into the free path, scanning it unlicensed.
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
    fun `pull_request trigger is unfiltered so required Docker e2e checks never stall (QD-15343)`() {
        // The Default Branch ruleset marks every Docker e2e cell REQUIRED. If the pull_request trigger were
        // paths-filtered, a PR not touching linter-images would leave those required checks "Expected" forever
        // → merge limbo (only admin-bypass hides it today). Keep pull_request unfiltered so every PR runs the
        // matrix and the required checks always report. (Deferred: cheap change-gating — see QD-15343.)
        assertTrue(onTriggers.has("pull_request"), "workflow must trigger on pull_request")
        val pr = onTriggers["pull_request"]
        assertTrue(
            pr == null || pr.isNull || !pr.has("paths"),
            "pull_request must NOT be paths-filtered (required Docker e2e checks would stall): ${pr?.get("paths")}",
        )
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

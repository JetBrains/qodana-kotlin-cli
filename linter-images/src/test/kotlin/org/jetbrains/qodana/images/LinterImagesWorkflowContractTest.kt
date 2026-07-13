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

    /** e2e harness steps (`linterE2eTest`) whose `if:` carries the given `requires_license` domain fragment. */
    private fun harnessSteps(domain: String): List<JsonNode> =
        steps.filter { it.runScript().contains("linterE2eTest") && it.ifExpr().contains(domain) }

    private val licensedImageNames: Set<String>
        get() = cells.filter { it["requires_license"]?.asText() == "true" }.map { it["name"].asText() }.toSet()

    /** Absent version == the default. */
    private fun JsonNode.version(): String = this["version"]?.asText() ?: ""

    private fun clangMajors(): List<String> =
        Path
            .of("docker/clang-versions.txt")
            .readText()
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+"))[0] }
            .toList()
            .sorted()

    @Test
    fun `cpp builds every clang major on both arches`() {
        val cpp = cells.filter { it["name"].asText() == "qodana-cpp" }
        val cppDefaultClang = EnvContract.parseEnv("qodana-cpp").getValue("CLANG")
        // Effective version per cell = its version, or the .env default when absent.
        val versionsByArch =
            cpp
                .groupBy { it["arch"].asText() }
                .mapValues { (_, g) -> g.map { it.version().ifEmpty { cppDefaultClang } }.sorted() }
        assertEquals(clangMajors(), versionsByArch["amd64"], "cpp must cover every clang major on amd64")
        assertEquals(clangMajors(), versionsByArch["arm64"], "cpp must cover every clang major on arm64")
    }

    @Test
    fun `clang builds every clang major on both arches, token-gated`() {
        val clang = cells.filter { it["name"].asText() == "qodana-clang" }
        val clangDefault = EnvContract.parseEnv("qodana-clang").getValue("CLANG")
        assertTrue(clang.all { it["token_gated"].asText() == "true" }, "every clang cell stays token-gated")
        // Effective version per cell = its version, or the .env default when absent (mirrors cpp).
        val versionsByArch =
            clang
                .groupBy { it["arch"].asText() }
                .mapValues { (_, g) -> g.map { it.version().ifEmpty { clangDefault } }.sorted() }
        assertEquals(clangMajors(), versionsByArch["amd64"], "clang must cover every clang major on amd64")
        assertEquals(clangMajors(), versionsByArch["arm64"], "clang must cover every clang major on arm64")
    }

    @Test
    fun `token-gated cells exist (clang and cdnet)`() {
        val names =
            cells
                .filter { it["token_gated"]?.asText() == "true" }
                .map { it["name"].asText() }
        assertTrue(names.containsAll(listOf("qodana-clang", "qodana-cdnet")), "token-gated cells: $names")
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
    fun `release-smoke builds via the action unconditionally with no Space token`() {
        val job = workflow["jobs"]["release-smoke"]
        assertTrue(job != null, "images.yaml must define a release-smoke job")
        assertEquals("Release profile smoke", job!!["name"].asText(), "release-smoke display name is linter-agnostic")
        val build = job["steps"].toList().single { it["uses"]?.asText() == "./.github/actions/build-linter-image" }
        assertEquals("", build.ifExpr(), "the release build must be unconditional so it always runs")
        val w = build["with"] ?: error("release-smoke build needs a with: block")
        assertTrue(
            w["compose-files"].asText().contains("compose.release.yaml"),
            "release-smoke must build through the release overlay: ${w["compose-files"].asText()}",
        )
        // No Space token: a silently-unapplied overlay falls back to the internal sha256 feed and fails RED.
        assertTrue(
            w["space-packages-token"] == null || w["space-packages-token"].asText().isBlank(),
            "release-smoke must pass an empty space-packages-token: ${w["space-packages-token"]}",
        )
        assertTrue(job["env"]?.get("QODANA_READ_SPACE_PACKAGES_TOKEN") == null, "release-smoke must not carry the Space token")
        // NO SILENT SKIPS: no step may skip-guard on an empty secret or a fork signal.
        job["steps"].toList().forEach { s ->
            val n = s["name"]?.asText()
            assertFalse(s.ifExpr().contains("''"), "$n: no release-smoke step may skip-guard on an empty secret")
            assertFalse(s.ifExpr().contains("head.repo.fork"), "$n: no release-smoke step may be fork-gated")
        }
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
        cells.groupBy { it["name"].asText() to it.version() }.forEach { (id, group) ->
            val (name, _) = id
            val arches = group.map { it["arch"].asText() }.sorted()
            val expected = if (name in ArchContract.archCapable) listOf("amd64", "arm64") else listOf("amd64")
            assertEquals(expected, arches, "$id must have exactly $expected")
        }
        cells.filter { it["arch"]?.asText() == "arm64" }.forEach {
            val n = it["name"].asText()
            assertTrue(it["runner"].asText().endsWith("-arm"), "$n: arm64 cell must use an -arm runner")
        }
    }

    @Test
    fun `ruby is one family with version cells matching ruby-versions_txt`() {
        val ruby = cells.filter { it["name"].asText() == "qodana-ruby" }
        val variantFamily = Regex("qodana-ruby-.*")
        assertTrue(cells.none { it["name"].asText().matches(variantFamily) }, "no qodana-ruby-X.Y families remain")
        val nonDefaultVersions = ruby.map { it.version() }.filter { it.isNotEmpty() }.toSet()
        val fileVersions =
            Path
                .of("docker/ruby-versions.txt")
                .readText()
                .lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map { it.split(Regex("\\s+")) }
        val fileNonDefault = fileVersions.filter { it.getOrNull(2) != "default" }.map { it[0] }.toSet()
        assertEquals(fileNonDefault, nonDefaultVersions, "ruby matrix non-default versions must equal the file's")
        // Every ruby version (default + each variant) must be a full amd64+arm64 pair, not a lone cell.
        ruby.groupBy { it.version() }.forEach { (v, group) ->
            val arches = group.map { it["arch"].asText() }.sorted()
            assertEquals(listOf("amd64", "arm64"), arches, "ruby version '$v' needs both arches")
        }
    }

    @Test
    fun `e2e job builds each cell through the build-linter-image action`() {
        assertTrue(
            steps.any { it["uses"]?.asText() == "./.github/actions/build-linter-image" },
            "e2e job must build via the shared action, not inline steps",
        )
    }

    @Test
    fun `the action receives the cell's matrix wiring and the secret inputs`() {
        val step = steps.single { it["uses"]?.asText() == "./.github/actions/build-linter-image" }
        val w = step["with"] ?: error("build-linter-image step needs a with: block")
        mapOf(
            "image" to "\${{ matrix.image.name }}",
            "version" to "\${{ matrix.image.version }}",
            "arch" to "\${{ matrix.image.arch }}",
            "token-gated" to "\${{ matrix.image.token_gated }}",
            "feed-required" to "\${{ matrix.image.feed_required }}",
            "compose-files" to "\${{ matrix.image.compose_files }}",
            "docker-registry-user" to "\${{ secrets.DOCKER_READ_PUBLIC_REGISTRY_USER }}",
            "docker-registry-token" to "\${{ secrets.DOCKER_READ_PUBLIC_REGISTRY_TOKEN }}",
            "space-packages-token" to "\${{ secrets.QODANA_READ_SPACE_PACKAGES_TOKEN }}",
        ).forEach { (k, v) -> assertEquals(v, w[k]?.asText(), "action input '$k'") }
    }

    @Test
    fun `the licensed scan step forwards the license token so an unlicensed scan fails loud`() {
        val licensed =
            steps.single {
                it.runScript().contains("linterE2eTest") && it.ifExpr().contains("requires_license == 'true'")
            }
        assertEquals(
            "\${{ secrets.QODANA_LICENSE_ONLY_TOKEN }}",
            licensed["env"]?.get("QODANA_LICENSE_ONLY_TOKEN")?.asText(),
            "the licensed scan must carry QODANA_LICENSE_ONLY_TOKEN in its step env (the harness errors on a blank passEnv)",
        )
    }

    @Test
    fun `the e2e scan runs after the build action (build then scan)`() {
        val actionIdx = steps.indexOfFirst { it["uses"]?.asText() == "./.github/actions/build-linter-image" }
        val scanIdx = steps.indexOfFirst { it.runScript().contains("linterE2eTest") }
        assertTrue(actionIdx in 0 until scanIdx, "the action ($actionIdx) must precede the scan ($scanIdx)")
    }

    @Test
    fun `every matrix image name is a real compose service`() {
        // Verifies the resolve-build-args else->none path can't mask a typo'd matrix.image.name: an unknown
        // name has no compose service, so `docker compose build <name>` would red. Bind it here too.
        val services =
            YAMLMapper()
                .readTree(Path.of("compose.yaml").readText())["services"]
                .fieldNames()
                .asSequence()
                .toSet()
        cells.map { it["name"].asText() }.toSet().forEach {
            assertTrue(it in services, "matrix image '$it' must be a compose.yaml service (else build reds): $services")
        }
    }

    @Test
    fun `no version cell explicitly pins its family default`() {
        // A pinned-default cell (e.g. cpp version:"20") would silently duplicate the un-versioned default cell.
        // The default is expressed by OMITTING version.
        val defaults =
            mapOf(
                "qodana-cpp" to EnvContract.parseEnv("qodana-cpp").getValue("CLANG"),
                "qodana-clang" to EnvContract.parseEnv("qodana-clang").getValue("CLANG"),
                "qodana-ruby" to
                    Path
                        .of("docker/ruby-versions.txt")
                        .readText()
                        .lineSequence()
                        .map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }
                        .map { it.split(Regex("\\s+")) }
                        .single { it.getOrNull(2) == "default" }[0],
            )
        cells.forEach { c ->
            val n = c["name"].asText()
            val d = defaults[n] ?: return@forEach
            assertTrue(c.version() != d, "$n must OMIT version for its default ($d), not pin it explicitly")
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
    fun `each arch-capable image's arm64 cell has the same gating keys and values as its amd64 cell`() {
        cells
            .filter { it["name"].asText() in ArchContract.archCapable }
            .groupBy { it["name"].asText() to it.version() }
            .forEach { (id, group) ->
                val byArch = group.associateBy { it["arch"].asText() }
                val amd64 = byArch.getValue("amd64")
                val arm64 = byArch.getValue("arm64")
                assertEquals(
                    amd64.fieldNames().asSequence().toSet(),
                    arm64.fieldNames().asSequence().toSet(),
                    "$id: arm64 cell must declare exactly the same keys as amd64",
                )
                amd64.fieldNames().asSequence().filter { it !in setOf("arch", "runner") }.forEach { k ->
                    assertEquals(amd64[k].asText(), arm64[k].asText(), "$id '$k': arm64 must match amd64")
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
    fun `pull_request trigger is unfiltered so the required Images gate never stalls (QD-15343)`() {
        // The required `Images` gate aggregates the e2e matrix. If the pull_request trigger were
        // paths-filtered, a PR not touching linter-images would leave the gate "Expected" forever
        // → merge limbo (only admin-bypass hides it today). Keep pull_request unfiltered so every PR runs the
        // matrix and the gate always reports. (Deferred: cheap change-gating — see QD-15343.)
        assertTrue(onTriggers.has("pull_request"), "workflow must trigger on pull_request")
        val pr = onTriggers["pull_request"]
        assertTrue(
            pr == null || pr.isNull || !pr.has("paths"),
            "pull_request must NOT be paths-filtered (the required Images gate would stall): ${pr?.get("paths")}",
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

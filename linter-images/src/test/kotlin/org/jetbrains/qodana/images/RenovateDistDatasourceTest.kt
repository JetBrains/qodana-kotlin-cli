package org.jetbrains.qodana.images

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Guards the QD_BUILD -> Renovate migration (QD-15370). Offline structural + content-regex checks on
 * parsed json5; the transform's semantic correctness against the real feed is the credentialed Task-2
 * dry-run, out of offline scope (cf. EslintPinTest). CWD is the module root, so the repo-root config is `../`.
 */
class RenovateDistDatasourceTest {
    private val imagesDir: Path = Path.of("docker/images")
    private val cfg: JsonNode =
        JsonMapper
            .builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()
            .readTree(Path.of("../.github/renovate.json5").toFile())

    // Pinned byte-for-byte: any edit reddens this, forcing a fresh Task-2 dry-run (the real-engine check
    // of what the transform actually selects) rather than a silent change.
    private val expectedTransform =
        """{ "releases": [Releases[MajorVersion = "2026.3" and Build]].{ "version": Build, "releaseTimestamp": Date } }"""

    /** Every `.env` that pins an IDE dist (has QD_LINTER_SLUG). */
    private fun ideDistEnvs(): List<Path> =
        Files.list(imagesDir).use { s ->
            s
                .filter { it.name.endsWith(".env") }
                .filter { EnvContract.parseEnv(it.name.removeSuffix(".env")).containsKey("QD_LINTER_SLUG") }
                .sorted()
                .toList()
        }

    /** The phase-0 pin key for an image `.env` — the naming rule that outlived BumpPinsCommand; the
     *  content-regex test below cross-checks it against renovate.json5's actual manager matchStrings. */
    private fun phase0Key(envFileName: String): String {
        val pinName = envFileName.removeSuffix(".env").replace(Regex("""-\d+\.\d+$"""), "")
        return "QODANA_${pinName.removePrefix("qodana-").uppercase().replace('-', '_')}_BUILD"
    }

    private fun distManagers(): List<JsonNode> =
        cfg.path("customManagers").filter { it.path("datasourceTemplate").asText() == "custom.qodana-dist" }

    private fun manager(slug: String): JsonNode {
        val ms = cfg.path("customManagers").filter { it.path("depNameTemplate").asText() == slug }
        assertEquals(1, ms.size, "exactly one customManagers entry must have depNameTemplate=$slug")
        return ms.single()
    }

    @Test
    fun `qodana-dist fetches the internal nightly feed per slug`() {
        val ds = cfg.path("customDatasources").path("qodana-dist")
        assertTrue(!ds.isMissingNode, "renovate.json5 must define the qodana-dist custom datasource")
        assertEquals(
            "https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed/{{packageName}}.releases.json",
            ds.path("defaultRegistryUrlTemplate").asText(),
            "qodana-dist must fetch <feed>/<slug>.releases.json",
        )
        assertEquals(
            expectedTransform,
            ds.path("transformTemplates").path(0).asText(),
            "transform is pinned; if you change it, re-run the Task-2 dry-run and update this expectation",
        )
    }

    @Test
    fun `the datasource major filter equals every IDE-dist QD_VERSION`() {
        val transform =
            cfg
                .path("customDatasources")
                .path("qodana-dist")
                .path("transformTemplates")
                .path(0)
                .asText()
        val major =
            Regex("""MajorVersion = "(\d{4}\.\d+)"""").find(transform)?.groupValues?.get(1)
                ?: error("qodana-dist transform must filter by MajorVersion = \"<major>\"")
        ideDistEnvs().forEach { env ->
            val slug = env.name.removeSuffix(".env")
            assertEquals(
                major,
                EnvContract.parseEnv(slug).getValue("QD_VERSION"),
                "qodana-dist major '$major' must equal $slug QD_VERSION (bump the datasource on a major migration)",
            )
        }
    }

    @Test
    fun `every IDE-dist QD_BUILD carries a qodana-dist renovate comment for its dist slug`() {
        // depNameTemplate is authoritative for Renovate; this asserts the comment's breadcrumb depName stays
        // consistent with the slug (hence with the manager's depNameTemplate, checked by the ownership test).
        ideDistEnvs().forEach { env ->
            val slug = EnvContract.parseEnv(env.name.removeSuffix(".env")).getValue("QD_LINTER_SLUG")
            val m =
                Regex("""# renovate: datasource=(\S+) depName=(\S+) versioning=\S+\s+QD_BUILD=""").find(env.readText())
                    ?: error("${env.name}: QD_BUILD needs a `# renovate:` comment directly above it")
            assertEquals("custom.qodana-dist", m.groupValues[1], "${env.name}: datasource")
            assertEquals(slug, m.groupValues[2], "${env.name}: depName must be the dist slug")
        }
    }

    @Test
    fun `each dist slug's manager owns its env files and phase-0 rows`() {
        // Group IDE-dist .env files by dist slug (android shares qodana-jvm, ruby trio shares qodana-ruby).
        // Each group maps to exactly one manager (depNameTemplate=slug, authoritative) that owns all the
        // group's .env files + phase-0 rows. Derived from the .env files — the single source of truth.
        ideDistEnvs()
            .groupBy { EnvContract.parseEnv(it.name.removeSuffix(".env")).getValue("QD_LINTER_SLUG") }
            .forEach { (slug, envs) ->
                val m = manager(slug)
                val patterns = m.path("managerFilePatterns").map { it.asText() }.toSet()
                assertTrue(
                    "linter-images/docs/phase-0-decisions.md" in patterns,
                    "$slug manager must own phase-0-decisions.md",
                )
                envs.forEach { e ->
                    assertTrue("linter-images/docker/images/${e.name}" in patterns, "$slug manager must own ${e.name}")
                }
                val matchStrings = m.path("matchStrings").joinToString("\n") { it.asText() }
                envs.map { phase0Key(it.name) }.toSet().forEach { key ->
                    assertTrue(
                        "$key = " in matchStrings,
                        "$slug manager must declare a matchString for the $key phase-0 row",
                    )
                }
            }
    }

    @Test
    fun `each manager's matchStrings match their real files exactly`() {
        // The strong guard: compile every qodana-dist manager's matchStrings and apply them to the ACTUAL
        // .env + phase-0 content. Catches anchor/whitespace drift, a typo'd capture, a misrouted or renamed
        // row, or a missing comment — the silent partial-bump the default `any` strategy would otherwise
        // allow (reproduces BumpPinsCommand.syncDecisions' `matched == 1`). Kotlin Regex ≈ Renovate's JS
        // RegExp for these patterns; the real engine is confirmed by the Task-2 dry-run.
        val managers = distManagers()
        assertEquals(11, managers.size, "expected 11 qodana-dist managers")
        managers.forEach { m ->
            val slug = m.path("depNameTemplate").asText()
            val files = m.path("managerFilePatterns").map { Path.of("..", it.asText()) }
            val contents = files.map { it.readText() }
            val envCount = files.count { it.name.endsWith(".env") }
            m.path("matchStrings").map { it.asText() }.forEach { pat ->
                val rx = Regex(pat)
                val total = contents.sumOf { rx.findAll(it).count() }
                val expected = if ("QD_BUILD=" in pat) envCount else 1
                assertEquals(expected, total, "$slug matchString must match exactly $expected line(s): /$pat/")
            }
        }
    }

    @Test
    fun `the drift workflow is retired`() {
        assertFalse(
            Files.exists(Path.of("../.github/workflows/linter-images-drift.yaml")),
            "linter-images-drift.yaml must be deleted (QD_BUILD bumping moved to Renovate)",
        )
    }
}

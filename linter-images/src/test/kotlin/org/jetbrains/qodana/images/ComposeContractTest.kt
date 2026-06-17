package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class ComposeContractTest {
    // Test working dir is the module root (pinned in Phase 1) — resolve relative to it.
    private val mapper = YAMLMapper()

    private fun load(name: String): JsonNode = mapper.readTree(Path.of(name).readText())

    private val slugs = listOf("qodana-jvm", "qodana-jvm-community", "qodana-android", "qodana-clang", "qodana-cdnet")

    @Test
    fun `compose defines a service per linter with docker context, tooling context, release source, dev tag`() {
        val root = load("compose.yaml")
        val services = root["services"]
        assertEquals(slugs.toSet(), services.fieldNames().asSequence().toSet())
        for (slug in slugs) {
            val svc = services[slug]
            val build = svc["build"]
            assertEquals("docker", build["context"].asText(), "$slug context must be docker/")
            assertEquals(
                "images/$slug.dockerfile",
                build["dockerfile"].asText(),
                "$slug dockerfile must be context-relative",
            )
            assertEquals("release", build["args"]["CLI_SOURCE"].asText(), "compose.yaml is the release path")
            // Canonical: every service tags <slug>:dev (both compose files; smoke/dogfood reference :dev).
            assertEquals("$slug:dev", svc["image"].asText(), "$slug must tag <slug>:dev")
            // tooling is ALWAYS required (installDist tree) and is defined in the BASE compose file.
            assertTrue(
                build["additional_contexts"].has("tooling"),
                "$slug must define the tooling build context in compose.yaml",
            )
            // Public compose must NOT reference a cli context (CLI_SOURCE=release downloads it)...
            assertTrue(
                build["additional_contexts"]["cli"] == null,
                "$slug compose.yaml must not define a cli context (release path)",
            )
            // ...and must NOT define/reference a feed_token secret — a bare public build runs with
            // QD_FEED_TOKEN unset (dist.dockerfile mounts it required=false).
            assertTrue(svc["secrets"] == null, "$slug must not reference a feed_token secret in compose.yaml")
        }
        assertTrue(root["secrets"] == null, "compose.yaml must not declare any secrets (private overlay does)")
    }

    @Test
    fun `release path provides a non-empty CLI_RELEASE_BASE_URL so the inner CLI download cannot 404 silently`() {
        // Strengthening beyond the plan's verbatim test: install-cli forms the asset URL as
        // ${CLI_RELEASE_BASE_URL}/qodana_<os>_<arch>.tar.gz, so a missing/blank base URL is a
        // guaranteed build break that the structural assertions above would not catch.
        val build = load("compose.yaml")["services"]["qodana-jvm"]["build"]
        val baseUrl = build["args"]["CLI_RELEASE_BASE_URL"]
        assertTrue(
            baseUrl != null && baseUrl.asText().isNotBlank(),
            "qodana-jvm must set a non-empty CLI_RELEASE_BASE_URL",
        )
        assertTrue(
            baseUrl.asText().startsWith("https://"),
            "CLI_RELEASE_BASE_URL must be an absolute https URL, was: '${baseUrl.asText()}'",
        )
    }

    @Test
    fun `clang layers the inner CLI onto the tools stage (no dist), via a compose build arg`() {
        // Clang/cdnet have NO dist stage: base.dockerfile defaults CLI_BASE_STAGE=dist, so they MUST
        // override it to `tools` (a build ARG, not an .env key) or the cli stage's `FROM
        // ${CLI_BASE_STAGE}` resolves to a non-existent `dist` stage and the build breaks.
        val root = load("compose.yaml")["services"]
        for (slug in listOf("qodana-clang", "qodana-cdnet")) {
            val args = root[slug]["build"]["args"]
            assertEquals("tools", args["CLI_BASE_STAGE"].asText(), "$slug must build CLI onto the tools stage")
        }
        // jvm/jvm-community/android have a dist stage; they must NOT override CLI_BASE_STAGE (it stays the
        // `dist` default).
        for (slug in listOf("qodana-jvm", "qodana-jvm-community", "qodana-android")) {
            val args = root[slug]["build"]["args"]
            assertTrue(args["CLI_BASE_STAGE"] == null, "$slug must not override CLI_BASE_STAGE (defaults to dist)")
        }
        // PRIVILEGED_BASE_STAGE: base.dockerfile defaults it to `clang-toolchain`. cdnet's privileged
        // layer sits on the .NET toolchain, so cdnet MUST override it to `dotnet-toolchain`. clang relies
        // on the global default and jvm/android have no privileged stage — none of them may set it.
        assertEquals(
            "dotnet-toolchain",
            root["qodana-cdnet"]["build"]["args"]["PRIVILEGED_BASE_STAGE"].asText(),
            "cdnet's privileged layer sits on the .NET toolchain",
        )
        for (slug in listOf("qodana-jvm", "qodana-android", "qodana-clang")) {
            val args = root[slug]["build"]["args"]
            assertTrue(
                args["PRIVILEGED_BASE_STAGE"] == null,
                "$slug must not override PRIVILEGED_BASE_STAGE (clang relies on the global clang-toolchain default)",
            )
        }
    }

    @Test
    fun `clang release-path CLI_VERSION matches the release tag (the tool asset name embeds it)`() {
        // The qodana-clang/qodana-cdnet TOOL asset is `<binary>_<CLI_VERSION>_linux_amd64`
        // (CliArtifactResolver), so on --source release CLI_VERSION MUST equal the version segment of the
        // compose tag, or the download 404s. (Cli-kind jvm/android have no version in their
        // `qodana_<os>_<arch>.tar.gz` name, so they are exempt.) Guards the W2 path where the feed-less
        // linters pull their inner CLI from the nightly release.
        for (slug in listOf("qodana-clang", "qodana-cdnet")) {
            val build = load("compose.yaml")["services"][slug]["build"]
            val baseUrl = build["args"]["CLI_RELEASE_BASE_URL"].asText()
            val tag = baseUrl.substringAfterLast('/')
            val tagVersion = tag.removePrefix("v")

            val cliVersion =
                imagesEnv(slug)["CLI_VERSION"]
                    ?: error("$slug.env must set CLI_VERSION")
            assertEquals(
                tagVersion,
                cliVersion,
                "$slug CLI_VERSION must equal the CLI_RELEASE_BASE_URL tag version, or the tool asset 404s",
            )
        }
    }

    /**
     * Parse a per-slug `.env` into a key→value map. Loose by design (no shape/duplicate checks) —
     * EnvContractTest is the strict validator of the same files and runs in this suite, so a malformed
     * or duplicate-keyed `.env` fails there; this helper only needs to read one already-validated value.
     */
    private fun imagesEnv(slug: String): Map<String, String> =
        Path
            .of("docker/images/$slug.env")
            .readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val i = line.indexOf('=')
                line.substring(0, i) to line.substring(i + 1)
            }

    @Test
    fun `private overlay scopes each token to the services that consume it`() {
        // jvm/android consume the private IDE feed (feed_token); clang consumes ONLY the clang-tidy
        // mirror token (qodana_cli_deps_token) — it has no dist stage so it must NOT pull feed_token.
        val root = load("compose.private.yaml")
        val services = root["services"]
        assertEquals(slugs.toSet(), services.fieldNames().asSequence().toSet())

        fun secretsOf(slug: String): Set<String> {
            val secrets = services[slug]["build"]["secrets"]
            return secrets.asSequence().map { it.asText() }.toSet()
        }

        assertEquals(setOf("feed_token"), secretsOf("qodana-jvm"), "jvm uses only the feed token")
        assertEquals(
            setOf("feed_token"),
            secretsOf("qodana-jvm-community"),
            "jvm-community uses only the feed token",
        )
        assertEquals(setOf("feed_token"), secretsOf("qodana-android"), "android uses only the feed token")
        assertEquals(
            setOf("qodana_cli_deps_token"),
            secretsOf("qodana-clang"),
            "clang has no dist stage, so it must reference only the clang-tidy mirror token",
        )
        assertEquals(
            setOf("qodana_cli_deps_token"),
            secretsOf("qodana-cdnet"),
            "cdnet has no dist stage, so it must reference only the qodana-cli-deps mirror token (ReSharper CLT)",
        )
        // Both tokens are declared at the top level, sourced from their env vars.
        val declared = root["secrets"]
        assertEquals(
            setOf("feed_token", "qodana_cli_deps_token"),
            declared.fieldNames().asSequence().toSet(),
            "the overlay declares both build secrets",
        )
        assertEquals("QD_FEED_TOKEN", declared["feed_token"]["environment"].asText())
        assertEquals("QODANA_CLI_DEPS_TOKEN", declared["qodana_cli_deps_token"]["environment"].asText())
    }

    @Test
    fun `compose ci override switches to context source and adds only the cli context, no tag change`() {
        val services = load("compose.ci.yaml")["services"]
        assertEquals(slugs.toSet(), services.fieldNames().asSequence().toSet())
        for (slug in slugs) {
            val svc = services[slug]
            val build = svc["build"]
            assertEquals("context", build["args"]["CLI_SOURCE"].asText(), "ci override is the context path")
            assertTrue(build["additional_contexts"].has("cli"), "$slug ci must add the cli context")
            // ci override must NOT change the tag — it stays <slug>:dev from compose.yaml.
            val img = svc["image"]
            assertTrue(img == null || img.asText() == "$slug:dev", "$slug ci must not change the :dev tag")
        }
    }
}

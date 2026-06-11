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

    private val slugs = listOf("qodana-jvm", "qodana-android", "qodana-clang")

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

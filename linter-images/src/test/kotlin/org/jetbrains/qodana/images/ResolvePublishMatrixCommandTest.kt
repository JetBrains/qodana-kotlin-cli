package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

class ResolvePublishMatrixCommandTest {
    private val realRuntime =
        RuntimeResolver(
            Path.of("docker/images"),
            Path.of("docker/clang-versions.txt"),
            Path.of("docker/ruby-versions.txt"),
        )

    private fun gpFixture(): Path {
        val gp = Files.createTempFile("gradle", ".properties")
        Files.writeString(gp, "version=2026.2\n")
        return gp
    }

    private val cmd =
        ResolvePublishMatrixCommand(
            imagesDir = Path.of("docker/images"),
            clangVersions = Path.of("docker/clang-versions.txt"),
            rubyVersions = Path.of("docker/ruby-versions.txt"),
            meta = ResolveImageMetaCommand(imagesDir = Path.of("docker/images"), runtime = realRuntime),
            tags = ResolveTagsCommand(gradleProperties = gpFixture(), runtime = realRuntime),
        )

    private val cmdWithTags =
        ResolvePublishMatrixCommand(
            imagesDir = Path.of("docker/images"),
            clangVersions = Path.of("docker/clang-versions.txt"),
            rubyVersions = Path.of("docker/ruby-versions.txt"),
            meta = ResolveImageMetaCommand(imagesDir = Path.of("docker/images"), runtime = realRuntime),
            tags = ResolveTagsCommand(gradleProperties = gpFixture(), runtime = realRuntime),
        )

    private fun rowKey(r: Map<String, String>): List<String> =
        listOf(r.getValue("image"), r.getValue("version"), r.getValue("token_gated"), r.getValue("feed_required"))

    private fun cellKey(c: JsonNode): List<String> =
        listOf(
            c["name"].asText(),
            c["version"]?.asText() ?: "",
            c["token_gated"].asText(),
            c["feed_required"].asText(),
        )

    @Test
    fun `matrix equals the distinct images_yaml e2e cells, gating included`() {
        val actual = cmd.rows().map { rowKey(it) }.toSet()
        val imagesYaml = YAMLMapper().readTree(Path.of("../.github/workflows/images.yaml").readText())
        val cells = imagesYaml["jobs"]["e2e"]["strategy"]["matrix"]["image"]
        val expected = cells.map { cellKey(it) }.toSet()
        assertEquals(expected, actual, "the publish matrix must equal the distinct images.yaml e2e cells")
    }

    @Test
    fun `emits parseable JSON with the expected row shape`() {
        val parsed = ObjectMapper().readTree(ObjectMapper().writeValueAsString(cmd.rows()))
        assertTrue(parsed.isArray && parsed.size() >= 15, "expected a JSON array of >=15 rows")
        listOf("image", "version", "token_gated", "feed_required", "compose_files").forEach {
            assertTrue(parsed[0].has(it), "each row must carry '$it'")
        }
    }

    @Test
    fun `rowsWithTags augments each row with space-joined bare tags from the real grammar`() {
        val rows = cmdWithTags.rowsWithTags(channel = "nightly", id = "20260716")
        val clangDefault =
            rows.single { it["image"] == "qodana-clang" && it["version"] == "" }.getValue("tags").split(" ")
        assertTrue(clangDefault.contains("2026.2-nightly"), "default cell carries the moving tag")
        assertTrue(clangDefault.contains("2026.2-nightly.20260716"), "default cell carries the dated tag")
        assertTrue(
            clangDefault.contains("2026.2-nightly.20260716-clang19"),
            "default cell carries the suffixed dated tag",
        )
        val clang16 =
            rows.single { it["image"] == "qodana-clang" && it["version"] == "16" }.getValue("tags").split(" ")
        assertEquals(setOf("2026.2-nightly.20260716-clang16", "2026.2-nightly-clang16"), clang16.toSet())
    }
}

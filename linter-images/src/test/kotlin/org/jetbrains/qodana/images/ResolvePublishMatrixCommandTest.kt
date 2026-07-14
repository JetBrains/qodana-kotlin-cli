package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

class ResolvePublishMatrixCommandTest {
    private val cmd =
        ResolvePublishMatrixCommand(
            imagesDir = Path.of("docker/images"),
            clangVersions = Path.of("docker/clang-versions.txt"),
            rubyVersions = Path.of("docker/ruby-versions.txt"),
            meta =
                ResolveImageMetaCommand(
                    imagesDir = Path.of("docker/images"),
                    runtime =
                        RuntimeResolver(
                            Path.of("docker/images"),
                            Path.of("docker/clang-versions.txt"),
                            Path.of("docker/ruby-versions.txt"),
                        ),
                ),
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
}

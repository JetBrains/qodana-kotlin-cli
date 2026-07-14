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
 * Contract for the on-demand publish path (publish-image.yaml reusable + publish-image-dispatch.yaml):
 * multi-arch build+merge shape, the concurrency guard, the SHA-threading (no mutable-ref TOCTOU), and the
 * binding that keeps `resolve-image-meta`'s per-image gating in lock-step with the images.yaml matrix.
 */
class PublishWorkflowContractTest {
    private fun wf(file: String): JsonNode = YAMLMapper().readTree(Path.of("../.github/workflows/$file").readText())

    private val publish = wf("publish-image.yaml")
    private val dispatch = wf("publish-image-dispatch.yaml")

    private fun JsonNode.on(): JsonNode = this["on"] ?: this["true"] ?: error("no on: block")

    private fun JsonNode.runScript(): String = this["run"]?.asText() ?: ""

    private fun steps(
        wf: JsonNode,
        job: String,
    ): List<JsonNode> = wf["jobs"][job]["steps"].toList()

    // --- publish-image.yaml (reusable) ---------------------------------------------------------------

    @Test
    fun `publish-image is a reusable workflow_call`() {
        assertTrue(publish.on().has("workflow_call"), "publish-image must be workflow_call-triggered")
    }

    @Test
    fun `concurrency group keys on image, version, channel and id`() {
        val group = publish["concurrency"]["group"].asText()
        listOf("inputs.image", "inputs.version", "inputs.channel", "inputs.id").forEach {
            assertTrue(group.contains(it), "concurrency group must key on $it: $group")
        }
        assertEquals("false", publish["concurrency"]["cancel-in-progress"].asText(), "same-target publishes must serialize")
    }

    @Test
    fun `build matrix covers both arches on native runners`() {
        val arches = publish["jobs"]["build"]["strategy"]["matrix"]["arch"].map { it.asText() }.toSet()
        assertEquals(setOf("amd64", "arm64"), arches, "build must fan out over both arches")
    }

    @Test
    fun `build verifies the built arch and captures the digest via extract-digest`() {
        val scripts = steps(publish, "build").joinToString("\n") { it.runScript() }
        assertTrue(scripts.contains("docker image inspect --format '{{.Architecture}}'"), "build must verify the image arch")
        assertTrue(scripts.contains("image-tool extract-digest"), "build must capture the digest via extract-digest")
    }

    @Test
    fun `merge needs build and assembles the manifest from resolve-tags`() {
        val merge = publish["jobs"]["merge"]
        assertTrue(merge["needs"].asText() == "build" || merge["needs"].any { it.asText() == "build" }, "merge must need build")
        val scripts = steps(publish, "merge").joinToString("\n") { it.runScript() }
        assertTrue(scripts.contains("image-tool resolve-tags"), "merge must derive tags from resolve-tags")
        assertTrue(scripts.contains("imagetools create"), "merge must assemble a multi-arch manifest")
        assertTrue(scripts.contains("imagetools inspect"), "merge must assert the published manifest resolves both arches")
    }

    // --- publish-image-dispatch.yaml (on-demand) -----------------------------------------------------

    @Test
    fun `dispatch image options are exactly the compose services`() {
        val options =
            dispatch.on()["workflow_dispatch"]["inputs"]["image"]["options"].map { it.asText() }.toSet()
        val services =
            YAMLMapper().readTree(Path.of("compose.yaml").readText())["services"].fieldNames().asSequence().toSet()
        assertEquals(services, options, "the dispatch image choices must equal the compose services")
    }

    @Test
    fun `dispatch threads the pinned SHA as ref, not the mutable branch ref`() {
        val with = dispatch["jobs"]["publish"]["with"]
        assertEquals("\${{ needs.resolve.outputs.sha }}", with["ref"].asText(), "ref must be the pinned SHA (no TOCTOU)")
        assertFalse(with["ref"].asText().contains("github.ref"), "ref must NOT be the mutable branch ref")
    }

    @Test
    fun `dispatch publishes the snapshot channel with the normalized version and sha7 id`() {
        val with = dispatch["jobs"]["publish"]["with"]
        assertEquals("snapshot", with["channel"].asText())
        assertEquals("\${{ needs.resolve.outputs.version }}", with["version"].asText(), "version must be the normalized effective")
        assertEquals("\${{ needs.resolve.outputs.sha7 }}", with["id"].asText())
    }

    // --- resolve-image-meta <-> images.yaml matrix binding (no drift) --------------------------------

    @Test
    fun `resolve-image-meta gating matches every images_yaml e2e cell`() {
        val meta =
            ResolveImageMetaCommand(
                imagesDir = Path.of("docker/images"),
                runtime =
                    RuntimeResolver(
                        Path.of("docker/images"),
                        Path.of("docker/clang-versions.txt"),
                        Path.of("docker/ruby-versions.txt"),
                    ),
            )
        val cells =
            YAMLMapper()
                .readTree(Path.of("../.github/workflows/images.yaml").readText())["jobs"]["e2e"]["strategy"]["matrix"]["image"]
        cells.forEach { c ->
            val name = c["name"].asText()
            val version = c["version"]?.asText() ?: ""
            val m = meta.resolve(name, version)
            assertEquals(c["token_gated"].asText(), m.tokenGated.toString(), "$name/$version token_gated")
            assertEquals(c["feed_required"].asText(), m.feedRequired.toString(), "$name/$version feed_required")
        }
    }
}

package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Contract for the nightly image-publish stage in nightly.yaml: the matrix is single-sourced from
 * `resolve-publish-matrix`, and the fan-out threads each cell into the shared publish-image.yaml reusable
 * on the `nightly` channel with the compute job's pinned SHA + nightly id (no re-resolution of HEAD).
 */
class NightlyPublishContractTest {
    private val nightly: JsonNode = YAMLMapper().readTree(Path.of("../.github/workflows/nightly.yaml").readText())

    private fun job(name: String): JsonNode = nightly["jobs"][name] ?: error("no job '$name'")

    @Test
    fun `compute emits the nightly id the publish stage keys tags on`() {
        val id = job("compute")["outputs"]["nightly-id"].asText()
        assertEquals("\${{ steps.compute.outputs.nightly-id }}", id, "compute must expose nightly-id")
    }

    @Test
    fun `publish-matrix single-sources the matrix from resolve-publish-matrix`() {
        val m = job("publish-matrix")
        assertEquals(
            "\${{ steps.m.outputs.matrix }}",
            m["outputs"]["matrix"].asText(),
            "publish-matrix must expose the matrix output",
        )
        val script = m["steps"].joinToString("\n") { it["run"]?.asText() ?: "" }
        assertTrue("resolve-publish-matrix" in script, "the matrix must come from image-tool resolve-publish-matrix")
    }

    @Test
    fun `publish-images fans the resolved matrix through the shared publish-image reusable`() {
        val p = job("publish-images")
        assertEquals("./.github/workflows/publish-image.yaml", p["uses"].asText(), "must reuse publish-image.yaml")
        assertEquals(
            "\${{ fromJSON(needs.publish-matrix.outputs.matrix) }}",
            p["strategy"]["matrix"]["include"].asText(),
            "the fan-out must consume the single-sourced matrix",
        )
        val needs = p["needs"].map { it.asText() }.toSet()
        assertTrue(needs.containsAll(setOf("compute", "publish-matrix")), "must depend on compute + publish-matrix")
        assertEquals("inherit", p["secrets"].asText(), "the reusable needs inherited secrets (registry + feed tokens)")
    }

    @Test
    fun `publish-images publishes the nightly channel with the pinned sha and id, threading each cell`() {
        val with = job("publish-images")["with"]
        assertEquals("nightly", with["channel"].asText(), "the nightly stage publishes the nightly channel")
        assertEquals("\${{ needs.compute.outputs.sha }}", with["ref"].asText(), "build the pinned SHA, not HEAD")
        assertEquals("\${{ needs.compute.outputs.nightly-id }}", with["id"].asText(), "tag id = the nightly id")
        // Every per-cell field is threaded from the matrix, so gating stays in lock-step with images.yaml.
        assertEquals("\${{ matrix.image }}", with["image"].asText())
        assertEquals("\${{ matrix.version }}", with["version"].asText())
        assertEquals("\${{ matrix.token_gated }}", with["token-gated"].asText())
        assertEquals("\${{ matrix.feed_required }}", with["feed-required"].asText())
        assertEquals("\${{ matrix.compose_files }}", with["compose-files"].asText())
    }
}

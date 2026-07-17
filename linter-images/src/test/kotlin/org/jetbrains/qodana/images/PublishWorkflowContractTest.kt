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

    private fun scriptsOf(
        wf: JsonNode,
        job: String,
    ): String = wf["jobs"][job]["steps"].joinToString("\n") { it.runScript() }

    // --- publish-image.yaml (reusable) ---------------------------------------------------------------

    @Test
    fun `publish-image is a reusable workflow_call`() {
        assertTrue(publish.on().has("workflow_call"), "publish-image must be workflow_call-triggered")
    }

    @Test
    fun `concurrency group keys on image and the tags (the authoritative publish identity)`() {
        val group = publish["concurrency"]["group"].asText()
        assertTrue(group.contains("inputs.image"), "concurrency must key on image: $group")
        assertTrue(group.contains("inputs.tags"), "concurrency must key on the tags identity: $group")
        assertEquals("false", publish["concurrency"]["cancel-in-progress"].asText(), "same-target publishes serialize")
    }

    @Test
    fun `publish-image drops channel and id inputs (tags is the identity)`() {
        val inputs = publish.on()["workflow_call"]["inputs"]
        assertFalse(inputs.has("channel"), "channel input is subsumed by tags")
        assertFalse(inputs.has("id"), "id input is subsumed by tags")
    }

    @Test
    fun `publish-image declares a required tags input`() {
        val tags = publish.on()["workflow_call"]["inputs"]["tags"]
        assertTrue(tags != null && tags["required"].asBoolean(), "tags must be a required input")
    }

    @Test
    fun `build matrix covers both arches on native runners`() {
        val arches = publish["jobs"]["build"]["strategy"]["matrix"]["arch"].map { it.asText() }.toSet()
        assertEquals(setOf("amd64", "arm64"), arches, "build must fan out over both arches")
    }

    @Test
    fun `every dispatchable image is arch-capable, so the hardcoded both-arch matrix is valid`() {
        // publish-image's build matrix is a static [amd64, arm64] — valid only while EVERY dispatchable
        // image is multiarch. Bind that assumption to ArchContract so an image added to the dispatch
        // before its arm64 port lands fails here (forcing publish-image to be made arch-aware first).
        val options =
            dispatch.on()["workflow_dispatch"]["inputs"]["image"]["options"].map { it.asText() }
        options.forEach {
            assertTrue(it in ArchContract.archCapable, "dispatchable image '$it' must be in ArchContract.archCapable")
        }
    }

    @Test
    fun `build pushes by digest via the action's publish mode (no staging tags)`() {
        val step =
            publish["jobs"]["build"]["steps"].single {
                it["uses"]?.asText() == "./.github/actions/build-linter-image"
            }
        val pushRegistry = step["with"]["push-registry"].asText()
        assertEquals("\${{ env.REGISTRY }}", pushRegistry, "build must run the action in publish mode")
        // No staging tags anywhere in the build job (the child is pushed untagged, by digest).
        assertFalse(scriptsOf(publish, "build").contains("_staging"), "publish must not create staging tags")
    }

    @Test
    fun `merge is Gradle-free and applies the passed-in tags via retry`() {
        val merge = publish["jobs"]["merge"]
        val needsBuild = merge["needs"].asText() == "build" || merge["needs"].any { it.asText() == "build" }
        assertTrue(needsBuild, "merge needs build")
        val stepsText = merge["steps"].toString()
        assertFalse(stepsText.contains("setup-java"), "merge must not set up Java")
        assertFalse(stepsText.contains("gradlew"), "merge must not run Gradle")
        assertFalse(stepsText.contains("resolve-tags"), "merge must not compute tags (passed in)")
        assertTrue(stepsText.contains("inputs.tags"), "merge must consume the tags input")
        val assemblesAndAsserts = stepsText.contains("imagetools create") && stepsText.contains("imagetools inspect")
        assertTrue(assemblesAndAsserts, "merge assembles + asserts")
        assertTrue(stepsText.contains("actions/retry"), "the imagetools calls must be retry-wrapped")
    }

    // --- publish-image-dispatch.yaml (on-demand) -----------------------------------------------------

    @Test
    fun `dispatch image options are exactly the compose services`() {
        val options =
            dispatch.on()["workflow_dispatch"]["inputs"]["image"]["options"].map { it.asText() }.toSet()
        val services =
            YAMLMapper()
                .readTree(Path.of("compose.yaml").readText())["services"]
                .fieldNames()
                .asSequence()
                .toSet()
        assertEquals(services, options, "the dispatch image choices must equal the compose services")
    }

    @Test
    fun `dispatch threads the pinned SHA as ref, not the mutable branch ref`() {
        val ref = dispatch["jobs"]["publish"]["with"]["ref"].asText()
        assertEquals("\${{ needs.resolve.outputs.sha }}", ref, "ref must be the pinned SHA (no TOCTOU)")
        assertFalse(ref.contains("github.ref"), "ref must NOT be the mutable branch ref")
    }

    @Test
    fun `dispatch resolves bare tags and threads them, without channel or id`() {
        val resolve = dispatch["jobs"]["resolve"]["steps"].joinToString("\n") { it["run"]?.asText() ?: "" }
        assertTrue("resolve-tags" in resolve, "dispatch computes bare tags via resolve-tags")
        val with = dispatch["jobs"]["publish"]["with"]
        assertEquals("\${{ needs.resolve.outputs.tags }}", with["tags"].asText())
        assertFalse(with.has("channel") || with.has("id"), "channel/id are subsumed by tags")
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
        val cells = wf("images.yaml")["jobs"]["e2e"]["strategy"]["matrix"]["image"]
        cells.forEach { c ->
            val name = c["name"].asText()
            val version = c["version"]?.asText() ?: ""
            val m = meta.resolve(name, version)
            assertEquals(c["token_gated"].asText(), m.tokenGated.toString(), "$name/$version token_gated")
            assertEquals(c["feed_required"].asText(), m.feedRequired.toString(), "$name/$version feed_required")
        }
    }
}

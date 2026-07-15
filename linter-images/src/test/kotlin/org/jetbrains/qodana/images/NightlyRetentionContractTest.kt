package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

/** Contract for the nightly registry-retention job: runs after publish, dry-run-first. */
class NightlyRetentionContractTest {
    private fun readWorkflow(name: String): JsonNode = YAMLMapper().readTree(Path.of("$DIR/$name").readText())

    private val nightly = readWorkflow("nightly.yaml")
    private val publishImage = readWorkflow("publish-image.yaml")

    private fun job(name: String): JsonNode = nightly["jobs"][name] ?: error("no job '$name'")

    private companion object {
        const val DIR = "../.github/workflows"
    }

    @Test
    fun `retention needs publish-images with no redundant if`() {
        val r = job("retention")
        assertEquals(setOf("publish-images"), r["needs"].map { it.asText() }.toSet())
        assertNull(r["if"], "needs: already gates on publish-images success; an explicit if: would be dead weight")
    }

    @Test
    fun `retention prunes via image-tool in dry-run mode, not a live delete`() {
        val script = job("retention")["steps"].joinToString("\n") { it["run"]?.asText() ?: "" }
        assertTrue("prune-registry" in script, "must invoke image-tool prune-registry")
        assertTrue("--dry-run" in script, "must stay dry-run until a human reviews a real plan and flips it")
    }

    @Test
    fun `retention authenticates with the same kcli registry write credentials publish-image already uses`() {
        val login =
            publishImage["jobs"]["build"]["steps"].first {
                it["uses"]?.asText()?.startsWith("docker/login-action") == true
            }
        val env = job("retention")["env"]
        assertEquals(login["with"]["username"].asText(), env["DOCKER_WRITE_KCLI_REGISTRY_USER"].asText())
        assertEquals(login["with"]["password"].asText(), env["DOCKER_WRITE_KCLI_REGISTRY_TOKEN"].asText())
    }
}

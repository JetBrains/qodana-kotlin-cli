package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/** Contract for the reusable retry composite action: pure orchestration driven by retry.sh, injection-safe. */
class RetryActionContractTest {
    private val dir = Path.of("../.github/actions/retry")
    private val action: JsonNode = YAMLMapper().readTree(dir.resolve("action.yaml").readText())

    @Test
    fun `is a composite action that execs retry_sh`() {
        assertEquals("composite", action["runs"]["using"].asText())
        val step = action["runs"]["steps"].single()
        assertTrue(step["run"].asText().contains("retry.sh"), "must exec retry.sh")
    }

    @Test
    fun `passes run via env, never string-interpolated into the script (injection-safe)`() {
        val step = action["runs"]["steps"].single()
        assertEquals("\${{ github.action_path }}/retry.sh", step["run"].asText().trim())
        assertEquals("\${{ inputs.run }}", step["env"]["RUN"].asText(), "run must reach the script via \$RUN")
    }

    @Test
    fun `declares the documented inputs`() {
        val inputs = action["inputs"]
        listOf("run", "timeout-seconds", "initial-delay-seconds", "max-delay-seconds", "what").forEach {
            assertTrue(inputs.has(it), "missing input '$it'")
        }
        assertTrue(inputs["run"]["required"].asBoolean(), "run must be required")
    }

    @Test
    fun `retry_sh exists and is executable`() {
        val sh = dir.resolve("retry.sh")
        assertTrue(sh.toFile().canExecute(), "retry.sh must be executable")
    }
}

package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The build-linter-image composite action's contract: it builds one linter image for one arch, fails
 * loud on a missing required token, arch-verifies the staged CLI, and leaves the runtime guard as its
 * last step so a caller's scan/push tail runs strictly after build+guard. These invariants back both
 * images.yaml (e2e) and publish-image.yaml (Plan 2).
 */
class BuildLinterImageActionContractTest {
    private val path = Path.of("../.github/actions/build-linter-image/action.yaml")
    private val action: JsonNode = YAMLMapper().readTree(path.readText())
    private val steps: List<JsonNode> = action["runs"]["steps"].toList()

    private fun JsonNode.ifExpr() = this["if"]?.asText() ?: ""

    private fun JsonNode.runScript() = this["run"]?.asText() ?: ""

    private fun idxOf(pred: (JsonNode) -> Boolean) = steps.indexOfFirst(pred)

    private fun JsonNode.isBuild(): Boolean {
        val r = runScript()
        return r.contains("docker compose") && r.contains(" build ")
    }

    @Test
    fun `is a composite action`() {
        assertTrue(action["runs"]["using"].asText() == "composite", "must be a composite action")
    }

    @Test
    fun `every run step has a sentence-y name (never blank, never a raw flag)`() {
        steps.filter { it.has("run") }.forEach { s ->
            val name = s["name"]?.asText()
            assertTrue(!name.isNullOrBlank(), "a run step has no name: ${s.runScript().take(60)}")
            assertFalse(name!!.trim().startsWith("-"), "step name is a raw flag: '$name'")
            assertFalse(name.contains("--"), "step name embeds a raw flag: '$name'")
        }
    }

    @Test
    fun `feed-required empty-token gate is a single loud hard-fail`() {
        val gates =
            steps.filter {
                it.ifExpr().contains("inputs.feed-required == 'true'") &&
                    it.ifExpr().contains("inputs.space-packages-token == ''")
            }
        assertTrue(gates.size == 1, "exactly one feed-required gate")
        assertTrue(gates.single().runScript().contains("exit 1"), "gate must exit 1")
    }

    @Test
    fun `token-gated empty-token gate is a single loud hard-fail`() {
        val gates =
            steps.filter {
                it.ifExpr().contains("inputs.token-gated == 'true'") &&
                    it.ifExpr().contains("inputs.space-packages-token == ''")
            }
        assertTrue(gates.size == 1, "exactly one token-gated gate")
        assertTrue(gates.single().runScript().contains("exit 1"), "gate must exit 1")
    }

    @Test
    fun `no step re-guards on a non-empty token`() {
        // Structural (parsed if:), not raw-text: a re-guard is a step that SKIPS when the token is present.
        val reguarded = steps.filter { it.ifExpr().contains("space-packages-token != ''") }
        val names = reguarded.map { it["name"]?.asText() }
        assertTrue(reguarded.isEmpty(), "no step may skip on a present token: $names")
    }

    @Test
    fun `the action has no fork or actor gating`() {
        val text = path.readText()
        listOf("head.repo.fork", "github.actor", "triggering_actor", "author_association", "pull_request_target")
            .forEach { assertFalse(text.contains(it), "'$it' fork/actor gating is banned") }
    }

    @Test
    fun `resolve-build-args precedes the build, which interpolates BUILD_ARGS`() {
        val resolveIdx = idxOf { it.runScript().contains("resolve-build-args") }
        val buildIdx = idxOf { it.isBuild() }
        assertTrue(resolveIdx in 0 until buildIdx, "resolve ($resolveIdx) must precede build ($buildIdx)")
        assertTrue(steps[buildIdx].runScript().contains("\${BUILD_ARGS}"), "build must interpolate \${BUILD_ARGS}")
    }

    @Test
    fun `staging arch-verifies the staged CLI ELF against inputs_arch`() {
        val staging = steps.single { (it["name"]?.asText() ?: "").startsWith("Stage from-tree contexts") }
        val run = staging.runScript()
        assertTrue(run.contains("file -b"), "must inspect the staged ELF via file -b")
        assertTrue(run.contains("grep -q 'x86-64'") && run.contains("grep -q 'aarch64'"), "must verify amd64/arm64 ELF")
        assertTrue(run.contains("want='\${{ inputs.arch }}'"), "must verify against inputs.arch, not a hardcoded arch")
    }

    @Test
    fun `token-gated staging globs the inner CLI binary by inputs_arch, not a hardcoded amd64`() {
        val glob =
            Regex("""tool_binaries=\([^)]*\)""").find(path.readText())?.value
                ?: error("tool_binaries glob not found")
        assertTrue("_linux_\${{ inputs.arch }}" in glob, "must select inputs.arch: $glob")
        assertFalse("_linux_amd64" in glob, "must not hardcode amd64: $glob")
    }

    @Test
    fun `the runtime guard follows the build and is the action's last step`() {
        val buildIdx = idxOf { it.isBuild() }
        val guardIdx = idxOf { (it["name"]?.asText() ?: "").startsWith("Verify built runtime version") }
        assertTrue(guardIdx > buildIdx, "guard ($guardIdx) must follow build ($buildIdx)")
        assertTrue(guardIdx == steps.lastIndex, "guard must be the action's last step (caller's tail follows it)")
    }
}

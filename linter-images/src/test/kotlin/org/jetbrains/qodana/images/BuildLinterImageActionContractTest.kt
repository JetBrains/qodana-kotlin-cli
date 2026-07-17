package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/** Contract for the build-linter-image composite action, shared by images.yaml (e2e) and future publish callers. */
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

    private fun usesRetry(step: JsonNode): Boolean = step["uses"]?.asText() == "./.github/actions/retry"

    private fun JsonNode.effectiveRun(): String =
        this["run"]?.asText() ?: (if (usesRetry(this)) this["with"]?.get("run")?.asText() ?: "" else "")

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
        val resolveIdx = steps.indexOfFirst { it.effectiveRun().contains("resolve-build-args") }
        val buildIdx = idxOf { it.isBuild() }
        assertTrue(resolveIdx in 0 until buildIdx, "resolve ($resolveIdx) must precede build ($buildIdx)")
        assertTrue(steps[buildIdx].runScript().contains("\${BUILD_ARGS}"), "build must interpolate \${BUILD_ARGS}")
    }

    @Test
    fun `the two gradle steps and the runtime-guard pull are retry-wrapped`() {
        val cmds = steps.filter { usesRetry(it) }.joinToString("\n") { it["with"]?.get("run")?.asText() ?: "" }
        assertTrue(cmds.contains("resolve-build-args"), "resolve-build-args must run under retry")
        assertTrue(cmds.contains("assembleRelease"), "the from-tree assemble must run under retry")
        assertTrue(cmds.contains("docker pull"), "the runtime-guard pull must run under retry")
    }

    @Test
    fun `each retry-wrapped command self-classifies by exiting 75 on a transient signal`() {
        steps.filter { usesRetry(it) }.forEach { s ->
            assertTrue(
                s["with"]["run"].asText().contains("exit 75"),
                "retry step must be able to signal transient: ${s["name"]?.asText()}",
            )
        }
    }

    @Test
    fun `the gradle build steps raise timeout-seconds above a worst-case build`() {
        val gradleSteps = steps.filter { usesRetry(it) && it["with"]["run"].asText().contains("gradlew") }
        assertTrue(gradleSteps.isNotEmpty(), "expected retry-wrapped gradle steps")
        gradleSteps.forEach { s ->
            val t = s["with"]["timeout-seconds"]?.asText()?.toIntOrNull() ?: 600
            assertTrue(
                t >= 1800,
                "gradle retry needs timeout-seconds >= 1800 so a slow build outlives the per-attempt cap: " +
                    "${s["name"]?.asText()} = $t",
            )
        }
    }

    @Test
    fun `bake is NOT retry-wrapped`() {
        val bake = steps.single { it.runScript().contains("docker buildx bake") }
        assertFalse(usesRetry(bake), "bake must not be retry-wrapped (local array state; BuildKit self-retries pulls)")
    }

    @Test
    fun `staging arch-verifies the staged CLI ELF against inputs_arch`() {
        // The retry-wrapped assemble step shares the "Stage from-tree contexts" name prefix; disambiguate
        // to the plain follow-up step (it.has("run")) that carries the ELF/tool-staging checks.
        val staging =
            steps.single { (it["name"]?.asText() ?: "").startsWith("Stage from-tree contexts") && it.has("run") }
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

    @Test
    fun `the runtime guard also runs in publish mode, probing the pushed digest`() {
        // On-demand publish can build an arbitrary, un-e2e'd ref, so a mis-baked runtime (ARG-shadowing)
        // must fail loud there too — the guard must NOT be gated off when push-registry is set.
        val guard = steps.single { (it["name"]?.asText() ?: "").startsWith("Verify built runtime version") }
        assertFalse(guard.ifExpr().contains("push-registry == ''"), "guard must not be skipped in publish mode")
        assertTrue(guard.ifExpr().contains("EXPECT_TOOL != 'none'"), "guard still only runs when a runtime is expected")
        assertTrue(
            guard.runScript().contains("steps.build.outputs.digest"),
            "publish mode has no local :dev image — the guard must probe the pushed per-arch digest",
        )
    }
}

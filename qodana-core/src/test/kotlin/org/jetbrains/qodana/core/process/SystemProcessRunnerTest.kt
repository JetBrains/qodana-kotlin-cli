package org.jetbrains.qodana.core.process

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.ProcessSpec
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SystemProcessRunnerTest {

    private val runner = SystemProcessRunner()

    @Test
    fun `run echo command`() = runTest {
        val result = runner.run(ProcessSpec(
            command = "echo",
            args = listOf("hello", "world"),
        ))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hello world"))
    }

    @Test
    fun `run command with non-zero exit`() = runTest {
        val result = runner.run(ProcessSpec(
            command = "sh",
            args = listOf("-c", "exit 42"),
        ))
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `run captures stderr`() = runTest {
        val result = runner.run(ProcessSpec(
            command = "sh",
            args = listOf("-c", "echo error-output >&2"),
        ))
        assertTrue(result.stderr.contains("error-output"))
    }

    @Test
    fun `run with working directory`() = runTest {
        val result = runner.run(ProcessSpec(
            command = "pwd",
            workDir = java.nio.file.Path.of("/tmp"),
        ))
        assertEquals(0, result.exitCode)
        // On macOS /tmp is symlink to /private/tmp
        assertTrue(result.stdout.trim().endsWith("tmp"), "Expected /tmp but got: ${result.stdout.trim()}")
    }

    @Test
    fun `run with environment variables`() = runTest {
        val result = runner.run(ProcessSpec(
            command = "sh",
            args = listOf("-c", "echo \$TEST_VAR"),
            env = mapOf("TEST_VAR" to "my-value"),
        ))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("my-value"))
    }

    @Test
    fun `start and stream events`() = runTest(timeout = 10.seconds) {
        val process = runner.start(ProcessSpec(
            command = "sh",
            args = listOf("-c", "echo line1 && echo line2 && echo err >&2"),
        ))
        val events = process.events().toList()
        val exitCode = process.awaitExit()

        assertEquals(0, exitCode)
        val allText = events.joinToString("\n") { it.text }
        assertTrue(allText.contains("line1"))
        assertTrue(allText.contains("line2"))
    }

    @Test
    fun `start preserves carriage returns in output chunks`() = runTest(timeout = 10.seconds) {
        val process = runner.start(ProcessSpec(
            command = "sh",
            args = listOf("-c", "printf 'step1\\rstep2\\rdone\\n'"),
        ))
        val events = process.events().toList()
        val exitCode = process.awaitExit()

        assertEquals(0, exitCode)
        assertTrue(events.any { it.text.contains('\r') }, "Expected carriage returns in emitted chunks")
        assertTrue(events.joinToString("") { it.text }.contains("done"))
    }

    @Test
    fun `run command that does not exist returns non-zero or throws`() = runTest {
        try {
            val result = runner.run(ProcessSpec(
                command = "nonexistent-command-xyz",
            ))
            // If it doesn't throw, exit code should be non-zero
            assertTrue(result.exitCode != 0)
        } catch (_: Exception) {
            // Expected — command not found
        }
    }

    @Test
    fun `run multiline output`() = runTest {
        val result = runner.run(ProcessSpec(
            command = "sh",
            args = listOf("-c", "echo 'line1\nline2\nline3'"),
        ))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.lines().size >= 3)
    }

    @Test
    fun `run returns timeout placeholder on timeout`() = runTest {
        val result = runner.run(
            ProcessSpec(
                command = "sh",
                args = listOf("-c", "sleep 2"),
                timeout = Duration.ofMillis(100),
            )
        )
        assertEquals(1000, result.exitCode)
    }

    @Test
    fun `start returns timeout placeholder on timeout`() = runTest {
        val process = runner.start(
            ProcessSpec(
                command = "sh",
                args = listOf("-c", "sleep 2"),
                timeout = Duration.ofMillis(100),
            )
        )
        assertEquals(1000, process.awaitExit())
    }
}

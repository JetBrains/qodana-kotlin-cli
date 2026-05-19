package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.qodana.core.model.LogEvent
import org.jetbrains.qodana.core.model.ProcessResult
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.RunningProcess
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClocCommandParityTest {
    @Test
    fun `cloc invokes scc with default format and current directory`() {
        val runner =
            RecordingProcessRunner(
                ProcessResult(exitCode = 0, stdout = "scc output", stderr = ""),
            )
        val terminal = ClocRecordingTerminal()

        ClocCommand(terminal, runner).parse(emptyList())

        assertEquals(1, runner.specs.size)
        assertEquals("scc", runner.specs.single().command)
        assertEquals(listOf("--format", "tabular", "--cocomo", "."), runner.specs.single().args)
        assertTrue(terminal.lines.any { it.contains("scc output") })
    }

    @Test
    fun `cloc forwards output value and multiple project dirs as-is`() {
        val runner =
            RecordingProcessRunner(
                ProcessResult(exitCode = 0, stdout = "", stderr = ""),
            )
        val terminal = ClocRecordingTerminal()

        ClocCommand(terminal, runner).parse(
            listOf("-o", "JSON", "-i", "project-a", "-i", "project-b"),
        )

        assertEquals(
            listOf("--format", "JSON", "--cocomo", "project-a", "project-b"),
            runner.specs.single().args,
        )
    }

    @Test
    fun `cloc returns exit code 1 on scc process failure and prints stderr`() {
        val runner =
            RecordingProcessRunner(
                ProcessResult(exitCode = 3, stdout = "partial", stderr = "boom"),
            )
        val terminal = ClocRecordingTerminal()

        val error =
            assertFailsWith<ProgramResult> {
                ClocCommand(terminal, runner).parse(emptyList())
            }

        assertEquals(1, error.statusCode)
        assertTrue(terminal.errors.contains("boom"))
        assertTrue(terminal.lines.contains("partial"))
    }
}

private class RecordingProcessRunner(
    private val result: ProcessResult,
) : ProcessRunner {
    val specs = mutableListOf<ProcessSpec>()

    override suspend fun run(spec: ProcessSpec): ProcessResult {
        specs += spec
        return result
    }

    override suspend fun start(spec: ProcessSpec): RunningProcess =
        object : RunningProcess {
            override fun events() = emptyFlow<LogEvent>()

            override suspend fun awaitExit() = 0

            override fun terminate() = Unit
        }
}

private class ClocRecordingTerminal : Terminal {
    val lines = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun print(message: String) {
        lines.add(message)
    }

    override fun println(message: String) {
        lines.add(message)
    }

    override fun error(message: String) {
        errors.add(message)
    }

    override fun info(message: String) {
        lines.add(message)
    }

    override fun warn(message: String) {
        lines.add(message)
    }

    override fun debug(message: String) {
        lines.add(message)
    }

    override fun <T> spinner(
        message: String,
        action: () -> T,
    ): T = action()

    override fun prompt(
        message: String,
        default: String?,
    ): String = default ?: ""

    override fun select(
        message: String,
        choices: List<String>,
    ): String = choices.first()

    override val isInteractive = false
    override var isCi = false

    override fun setRedactedTokens(tokens: Set<String>) {}
}

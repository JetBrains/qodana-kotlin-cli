package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.model.ScanContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScanCommandTest {
    @Test
    fun `show report invokes display with default port`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command = ScanCommand(
            scanRunner = { 0 },
            terminal = NoOpTerminal(),
            scanReportDisplay = reportDisplay,
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("-i", projectDir.toString(), "--show-report"))
        }

        assertEquals(0, result.statusCode)
        assertEquals(1, reportDisplay.calls.size)
        assertEquals(8080, reportDisplay.calls.single().port)
    }

    @Test
    fun `show report port wins over deprecated port`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command = ScanCommand(
            scanRunner = { 0 },
            terminal = NoOpTerminal(),
            scanReportDisplay = reportDisplay,
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(
                listOf(
                    "-i", projectDir.toString(),
                    "--show-report",
                    "--port", "9002",
                    "--show-report-port", "9003",
                )
            )
        }

        assertEquals(0, result.statusCode)
        assertEquals(9003, reportDisplay.calls.single().port)
    }

    @Test
    fun `deprecated port used when show report port is absent`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command = ScanCommand(
            scanRunner = { 0 },
            terminal = NoOpTerminal(),
            scanReportDisplay = reportDisplay,
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("-i", projectDir.toString(), "--show-report", "--port", "9004"))
        }

        assertEquals(0, result.statusCode)
        assertEquals(9004, reportDisplay.calls.single().port)
    }

    @Test
    fun `display is not invoked without show report flag`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command = ScanCommand(
            scanRunner = { 0 },
            terminal = NoOpTerminal(),
            scanReportDisplay = reportDisplay,
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("-i", projectDir.toString()))
        }

        assertEquals(0, result.statusCode)
        assertTrue(reportDisplay.calls.isEmpty())
    }

    @Test
    fun `show report failure changes exit code when scan succeeded`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay(exitCode = 17)
        val command = ScanCommand(
            scanRunner = { 0 },
            terminal = NoOpTerminal(),
            scanReportDisplay = reportDisplay,
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("-i", projectDir.toString(), "--show-report"))
        }

        assertEquals(17, result.statusCode)
    }

    @Test
    fun `show report failure does not override scan failure`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay(exitCode = 17)
        val command = ScanCommand(
            scanRunner = { 3 },
            terminal = NoOpTerminal(),
            scanReportDisplay = reportDisplay,
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("-i", projectDir.toString(), "--show-report"))
        }

        assertEquals(3, result.statusCode)
    }

    @Test
    fun `native mode prepares effective config directory`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        Files.writeString(projectDir.resolve("qodana.yaml"), "version: \"1.0\"\n")
        var capturedContext: ScanContext? = null
        val command = ScanCommand(
            scanRunner = { context ->
                capturedContext = context
                0
            },
            terminal = NoOpTerminal(),
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("-i", projectDir.toString(), "--ide", "QDGO"))
        }

        assertEquals(0, result.statusCode)
        val context = requireNotNull(capturedContext)
        val effectiveConfigDir = requireNotNull(context.runtime.effectiveConfigDir)
        assertTrue(Files.exists(effectiveConfigDir))
        assertTrue(Files.exists(effectiveConfigDir.resolve("qodana.yaml")))
    }

    @Test
    fun `container mode keeps effective config directory unset`(@TempDir tmpDir: Path) {
        val projectDir = createProject(tmpDir)
        var capturedContext: ScanContext? = null
        val command = ScanCommand(
            scanRunner = { context ->
                capturedContext = context
                0
            },
            terminal = NoOpTerminal(),
        )

        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("-i", projectDir.toString()))
        }

        assertEquals(0, result.statusCode)
        assertEquals(null, requireNotNull(capturedContext).runtime.effectiveConfigDir)
    }

    private fun createProject(dir: Path): Path {
        Files.writeString(dir.resolve("main.kt"), "fun main() = Unit\n")
        return dir
    }
}

private class NoOpTerminal : Terminal {
    override val isInteractive: Boolean = false
    override var isCi: Boolean = false
    override fun print(message: String) {}
    override fun println(message: String) {}
    override fun error(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
    override fun debug(message: String) {}
    override fun <T> spinner(message: String, action: () -> T): T = action()
    override fun prompt(message: String, default: String?): String = default ?: ""
    override fun select(message: String, choices: List<String>): String = choices.first()
    override fun setRedactedTokens(tokens: Set<String>) {}
}

private class RecordingReportDisplay(
    private val exitCode: Int = 0,
) : ScanReportDisplay {
    data class Call(val resultsDir: Path, val reportDir: Path, val port: Int)
    val calls = mutableListOf<Call>()

    override fun show(resultsDir: Path, reportDir: Path, port: Int): Int {
        calls += Call(resultsDir, reportDir, port)
        return exitCode
    }
}

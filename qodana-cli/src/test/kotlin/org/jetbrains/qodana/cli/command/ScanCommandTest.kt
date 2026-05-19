package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.core.env.QodanaEnv
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
    fun `show report invokes display with default port`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString(), "--show-report"))
            }

        assertEquals(0, result.statusCode)
        assertEquals(1, reportDisplay.calls.size)
        assertEquals(8080, reportDisplay.calls.single().port)
    }

    @Test
    fun `show report port wins over deprecated port`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(
                    listOf(
                        "-i",
                        projectDir.toString(),
                        "--show-report",
                        "--port",
                        "9002",
                        "--show-report-port",
                        "9003",
                    ),
                )
            }

        assertEquals(0, result.statusCode)
        assertEquals(9003, reportDisplay.calls.single().port)
    }

    @Test
    fun `deprecated port used when show report port is absent`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString(), "--show-report", "--port", "9004"))
            }

        assertEquals(0, result.statusCode)
        assertEquals(9004, reportDisplay.calls.single().port)
    }

    @Test
    fun `display is not invoked without show report flag`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertEquals(0, result.statusCode)
        assertTrue(reportDisplay.calls.isEmpty())
    }

    @Test
    fun `interactive mode prompts and opens report when confirmed`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val terminal = RecordingTerminal(isInteractive = true, selection = "Yes")
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = terminal,
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertEquals(0, result.statusCode)
        assertEquals(1, terminal.selectCallCount)
        assertEquals(1, reportDisplay.calls.size)
    }

    @Test
    fun `interactive mode does not open report when declined`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val terminal = RecordingTerminal(isInteractive = true, selection = "No")
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = terminal,
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertEquals(0, result.statusCode)
        assertEquals(1, terminal.selectCallCount)
        assertTrue(reportDisplay.calls.isEmpty())
    }

    @Test
    fun `explicit show report bypasses interactive prompt`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay()
        val terminal = RecordingTerminal(isInteractive = true, selection = "No")
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = terminal,
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString(), "--show-report"))
            }

        assertEquals(0, result.statusCode)
        assertEquals(0, terminal.selectCallCount)
        assertEquals(1, reportDisplay.calls.size)
    }

    @Test
    fun `show report failure changes exit code when scan succeeded`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay(exitCode = 17)
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString(), "--show-report"))
            }

        assertEquals(17, result.statusCode)
    }

    @Test
    fun `show report failure does not override scan failure`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val reportDisplay = RecordingReportDisplay(exitCode = 17)
        val command =
            ScanCommand(
                scanRunner = { 3 },
                terminal = NoOpTerminal(),
                scanReportDisplay = reportDisplay,
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString(), "--show-report"))
            }

        assertEquals(3, result.statusCode)
    }

    @Test
    fun `native mode prepares effective config directory`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        Files.writeString(projectDir.resolve("qodana.yaml"), "version: \"1.0\"\n")
        var capturedContext: ScanContext? = null
        val command =
            ScanCommand(
                scanRunner = { context ->
                    capturedContext = context
                    0
                },
                terminal = NoOpTerminal(),
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString(), "--ide", "QDGO"))
            }

        assertEquals(0, result.statusCode)
        val context = requireNotNull(capturedContext)
        val effectiveConfigDir = requireNotNull(context.runtime.effectiveConfigDir)
        assertTrue(Files.exists(effectiveConfigDir))
        assertTrue(Files.exists(effectiveConfigDir.resolve("qodana.yaml")))
    }

    @Test
    fun `container mode keeps effective config directory unset`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        var capturedContext: ScanContext? = null
        val command =
            ScanCommand(
                scanRunner = { context ->
                    capturedContext = context
                    0
                },
                terminal = NoOpTerminal(),
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertEquals(0, result.statusCode)
        assertEquals(null, requireNotNull(capturedContext).runtime.effectiveConfigDir)
    }

    @Test
    fun `scan fails for unknown cli linter`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
            )

        val error =
            assertFailsWith<UsageError> {
                command.parse(listOf("-i", projectDir.toString(), "--linter", "super-linter"))
            }

        assertTrue(error.message.orEmpty().contains("Unrecognized '--linter' value"))
    }

    @Test
    fun `scan fails for unknown yaml linter`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        Files.writeString(projectDir.resolve("qodana.yaml"), "linter: super-linter\n")
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
            )

        val error =
            assertFailsWith<UsageError> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertTrue(error.message.orEmpty().contains("Unrecognized '--linter' value"))
    }

    @Test
    fun `scan accepts legacy image in linter parameter even with within docker false`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        var capturedContext: ScanContext? = null
        val command =
            ScanCommand(
                scanRunner = { context ->
                    capturedContext = context
                    0
                },
                terminal = NoOpTerminal(),
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(
                    listOf(
                        "-i",
                        projectDir.toString(),
                        "--linter",
                        "jetbrains/qodana-go:2025.2",
                        "--within-docker",
                        "false",
                    ),
                )
            }

        assertEquals(0, result.statusCode)
        val context = requireNotNull(capturedContext)
        assertEquals("qodana-go", context.linter)
        assertEquals("jetbrains/qodana-go:2025.2", context.docker.image)
        assertEquals(org.jetbrains.qodana.engine.model.DockerLauncherExecutionProfile, context.executionProfile)
    }

    @Test
    fun `scan fails when yaml contains both linter and ide`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        Files.writeString(
            projectDir.resolve("qodana.yaml"),
            """
            linter: qodana-jvm
            ide: QDJVM
            """.trimIndent(),
        )
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
            )

        val error =
            assertFailsWith<UsageError> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertTrue(error.message.orEmpty().contains("both `linter:`"))
    }

    @Test
    fun `scan fails when yaml contains both image and ide`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir)
        Files.writeString(
            projectDir.resolve("qodana.yaml"),
            """
            image: jetbrains/qodana-jvm:2025.3
            ide: QDJVM
            """.trimIndent(),
        )
        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
            )

        val error =
            assertFailsWith<UsageError> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertTrue(error.message.orEmpty().contains("both `image:`"))
    }

    @Test
    fun `scan fails when repository root does not contain project directory`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = tmpDir.resolve("project").also { Files.createDirectories(it) }
        Files.writeString(projectDir.resolve("main.kt"), "fun main() = Unit\n")
        val unrelatedRepositoryRoot = tmpDir.resolve("repo").also { Files.createDirectories(it) }

        val command =
            ScanCommand(
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
            )

        val error =
            assertFailsWith<UsageError> {
                command.parse(
                    listOf(
                        "-i",
                        projectDir.toString(),
                        "--repository-root",
                        unrelatedRepositoryRoot.toString(),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("must be located inside repository root"))
    }

    @Test
    fun `scan derives default results and report from cache dir hierarchy`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir.resolve("project").also { Files.createDirectories(it) })
        val cacheDir = tmpDir.resolve("custom/system/cache").also { Files.createDirectories(it) }
        var capturedContext: ScanContext? = null
        val command =
            ScanCommand(
                scanRunner = { context ->
                    capturedContext = context
                    0
                },
                terminal = NoOpTerminal(),
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(
                    listOf(
                        "-i",
                        projectDir.toString(),
                        "--cache-dir",
                        cacheDir.toString(),
                    ),
                )
            }

        assertEquals(0, result.statusCode)
        val context = requireNotNull(capturedContext)
        val expectedSystemDir =
            cacheDir
                .toAbsolutePath()
                .normalize()
                .parent.parent
        assertEquals(cacheDir.toAbsolutePath().normalize(), context.paths.cacheDir)
        assertEquals(expectedSystemDir, context.paths.resultsDir.parent.parent)
        assertEquals(context.paths.resultsDir.resolve("report"), context.paths.reportDir)
    }

    @Test
    fun `scan uses QODANA_DIST override before CLI analyzer options`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir.resolve("project").also { Files.createDirectories(it) })
        val distDir = tmpDir.resolve("dist").also { Files.createDirectories(it) }
        val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
        val productInfoDir = if (isMac) distDir.resolve("Resources").also { Files.createDirectories(it) } else distDir
        Files.writeString(productInfoDir.resolve("product-info.json"), """{"productCode":"GO"}""")
        var capturedContext: ScanContext? = null
        val command =
            ScanCommand(
                envOverrides = mapOf(QodanaEnv.DIST to distDir.toString()),
                scanRunner = { context ->
                    capturedContext = context
                    0
                },
                terminal = NoOpTerminal(),
            )

        val result =
            assertFailsWith<ProgramResult> {
                command.parse(
                    listOf(
                        "-i",
                        projectDir.toString(),
                        "--linter",
                        "qodana-jvm",
                    ),
                )
            }

        assertEquals(0, result.statusCode)
        val context = requireNotNull(capturedContext)
        assertEquals(org.jetbrains.qodana.engine.model.NativeExecutionProfile, context.executionProfile)
        assertEquals(distDir.toAbsolutePath().normalize(), context.runtime.ideDir)
    }

    @Test
    fun `scan fails when QODANA_DIST points to invalid distribution`(
        @TempDir tmpDir: Path,
    ) {
        val projectDir = createProject(tmpDir.resolve("project").also { Files.createDirectories(it) })
        val invalidDist = tmpDir.resolve("invalid-dist").also { Files.createDirectories(it) }
        val command =
            ScanCommand(
                envOverrides = mapOf(QodanaEnv.DIST to invalidDist.toString()),
                scanRunner = { 0 },
                terminal = NoOpTerminal(),
            )

        val error =
            assertFailsWith<UsageError> {
                command.parse(listOf("-i", projectDir.toString()))
            }

        assertTrue(error.message.orEmpty().contains("doesn't point to valid distribution"))
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

    override fun setRedactedTokens(tokens: Set<String>) {}
}

private class RecordingTerminal(
    override val isInteractive: Boolean,
    private val selection: String = "Yes",
) : Terminal {
    override var isCi: Boolean = false
    var selectCallCount: Int = 0

    override fun print(message: String) {}

    override fun println(message: String) {}

    override fun error(message: String) {}

    override fun info(message: String) {}

    override fun warn(message: String) {}

    override fun debug(message: String) {}

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
    ): String {
        selectCallCount++
        return selection
    }

    override fun setRedactedTokens(tokens: Set<String>) {}
}

private class RecordingReportDisplay(
    private val exitCode: Int = 0,
) : ScanReportDisplay {
    data class Call(
        val resultsDir: Path,
        val reportDir: Path,
        val port: Int,
    )

    val calls = mutableListOf<Call>()

    override fun show(
        resultsDir: Path,
        reportDir: Path,
        port: Int,
    ): Int {
        calls += Call(resultsDir, reportDir, port)
        return exitCode
    }
}

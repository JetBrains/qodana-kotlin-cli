package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.core.fs.NioFileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.engine.git.SystemGitClient
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.port.TokenStore
import org.jetbrains.qodana.engine.report.ReportProcessor
import org.jetbrains.qodana.engine.reportconverter.ReportConverterAdapter
import org.jetbrains.qodana.engine.scan.ContainerScan
import org.jetbrains.qodana.engine.scan.NativeScan
import org.jetbrains.qodana.engine.scan.ScanUseCase
import org.jetbrains.qodana.engine.startup.IdeInstaller
import org.jetbrains.qodana.engine.startup.PrepareHost
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class QodanaCommandTest {

    private val output = mutableListOf<String>()

    private val terminal = object : Terminal {
        override fun print(message: String) { output.add(message) }
        override fun println(message: String) { output.add(message) }
        override fun error(message: String) { output.add("ERROR: $message") }
        override fun info(message: String) { output.add("INFO: $message") }
        override fun warn(message: String) { output.add("WARN: $message") }
        override fun debug(message: String) { output.add("DEBUG: $message") }
        override fun <T> spinner(message: String, action: () -> T): T = action()
        override fun prompt(message: String, default: String?): String = default ?: ""
        override fun select(message: String, choices: List<String>): String = choices.first()
        override val isInteractive = false
        override var isCi = false
        override fun setRedactedTokens(tokens: Set<String>) {}
    }

    private fun createProject(dir: Path): Path {
        Files.createDirectories(dir)
        dir.resolve("hello.py").writeText("print(\"Hello\"   )")
        dir.resolve(".idea").createDirectories()
        return dir
    }

    private fun createInitCommandForProjectDetection(): InitCommand {
        val emptyTokenStore = object : TokenStore {
            override fun load(key: String): String? = null
            override fun save(key: String, value: String) = Unit
            override fun delete(key: String) = Unit
        }
        return InitCommand(
            terminal = terminal,
            getEnv = { null },
            tokenStore = emptyTokenStore,
        )
    }

    private fun isDockerAvailable(): Boolean {
        return try {
            val engine = DockerJavaEngine()
            kotlinx.coroutines.runBlocking { engine.info() }
            true
        } catch (_: Exception) {
            false
        }
    }

    // -- Version / Help --

    @Test
    fun `version flag prints version`() {
        val exception = assertFailsWith<PrintMessage> {
            QodanaCommand().parse(listOf("--version"))
        }
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains(QodanaCommand.VERSION))
    }

    @Test
    fun `help flag prints help`() {
        val withFlag = assertFailsWith<PrintHelpMessage> {
            QodanaCommand().parse(listOf("--help"))
        }
        val helpText = withFlag.context?.command?.getFormattedHelp() ?: ""
        assertTrue(helpText.contains("Qodana CLI"))
    }

    @Test
    fun `help text is same with flag and without args`() {
        val withFlag = assertFailsWith<PrintHelpMessage> {
            QodanaCommand().parse(listOf("--help"))
        }
        val helpWithFlag = withFlag.context?.command?.getFormattedHelp() ?: ""

        val command = QodanaCommand()
        command.parse(emptyList())
        val helpWithout = command.getFormattedHelp()

        assertEquals(helpWithFlag, helpWithout)
    }

    @Test
    fun `log-level sets slf4j property`() {
        QodanaCommand().parse(listOf("--log-level", "debug"))
        assertEquals("debug", System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"))
    }

    @Test
    fun `unknown subcommand fails`() {
        assertFailsWith<UsageError> {
            QodanaCommand().subcommands(
                ScanCommand { 0 },
            ).parse(listOf("nonexistent"))
        }
    }

    // -- InitCommand (mirrors Go's TestInitCommand) --

    @Test
    fun `init detects python project and updates yaml`(@TempDir dir: Path) {
        val projectPath = createProject(dir.resolve("qodana_init"))
        projectPath.resolve("qodana.yml").writeText("version: 1.0")

        val command = createInitCommandForProjectDetection()
        command.parse(listOf("-i", projectPath.toString()))

        val yamlFile = projectPath.resolve("qodana.yml")
        assertTrue(Files.exists(yamlFile), "qodana.yml should still exist")
        val content = Files.readString(yamlFile)
        assertTrue(
            content.contains(Linters.PYTHON.name),
            "Expected linter '${Linters.PYTHON.name}', but yaml was: $content"
        )
    }

    @Test
    fun `init creates new qodana yaml when none exists`(@TempDir dir: Path) {
        val projectPath = createProject(dir.resolve("qodana_new"))

        val command = createInitCommandForProjectDetection()
        command.parse(listOf("-i", projectPath.toString()))

        val yamlFile = projectPath.resolve("qodana.yaml")
        assertTrue(Files.exists(yamlFile), "qodana.yaml should be created")
        val content = Files.readString(yamlFile)
        assertTrue(content.contains(Linters.PYTHON.name), "Should detect Python linter")
        assertTrue(output.any { it.contains("Created") })
    }

    @Test
    fun `init detects java project`(@TempDir dir: Path) {
        val projectPath = dir.resolve("java_project")
        Files.createDirectories(projectPath)
        projectPath.resolve("Main.java").writeText("class Main {}")

        val command = createInitCommandForProjectDetection()
        command.parse(listOf("-i", projectPath.toString()))

        val yamlFile = projectPath.resolve("qodana.yaml")
        assertTrue(Files.exists(yamlFile))
        val content = Files.readString(yamlFile)
        assertTrue(content.contains("qodana-jvm"), "Should detect JVM linter, but was: $content")
    }

    // -- ScanCommand: mutual exclusion (mirrors Go's TestExclusiveFixesCommand) --

    @Test
    fun `scan with apply-fixes and cleanup fails`(@TempDir dir: Path) {
        assertFailsWith<UsageError> {
            ScanCommand { 0 }.parse(listOf("-i", dir.toString(), "--apply-fixes", "--cleanup"))
        }
    }

    // -- PullCommand: real Docker pull (mirrors Go's TestPullImage) --

    @Test
    fun `pull image pulls hello-world`() {
        assumeTrue(isDockerAvailable(), "Docker not available, skipping")

        val containerEngine = DockerJavaEngine()
        val command = PullCommand(containerEngine, terminal)
        command.parse(listOf("--image", "hello-world"))

        assertTrue(
            output.any { it.contains("hello-world") },
            "Should have pulled hello-world: $output"
        )
        assertTrue(
            output.any { it.contains("pulled successfully") },
            "Should confirm pull success: $output"
        )
    }

    // -- PullCommand: native mode skip (mirrors Go's TestPullInNative) --

    @Test
    fun `pull in native mode skips docker`(@TempDir dir: Path) {
        val projectPath = createProject(dir.resolve("qodana_scan_python_native"))
        projectPath.resolve("qodana.yaml").writeText("ide: QDPY")

        // Use real DockerJavaEngine — pull should never be called because
        // PullCommand detects native mode from the yaml ide field
        val containerEngine = DockerJavaEngine()
        val command = PullCommand(containerEngine, terminal)
        command.parse(listOf("-i", projectPath.toString()))

        assertTrue(
            output.any { it.contains("Native mode") },
            "Should detect native mode and skip pull: $output"
        )
    }

    // -- ContributorsCommand: real git analysis (mirrors Go's TestContributorsCommand) --

    @Test
    fun `contributors analyzes real git repo`() {
        val processRunner = SystemProcessRunner()
        val gitClient = SystemGitClient(processRunner)
        val analyzer = ContributorAnalyzer(gitClient)

        val command = ContributorsCommand(analyzer, terminal)
        command.parse(listOf("--days", "-1", "--output", "json"))

        val jsonOutput = output.joinToString("\n")
        assertTrue(jsonOutput.contains("\"total\""), "Should produce JSON output with total field")
        assertTrue(jsonOutput.contains("\"contributors\""), "Should contain contributors field")
    }

    @Test
    fun `contributors defaults to tabular output`() {
        val processRunner = SystemProcessRunner()
        val gitClient = SystemGitClient(processRunner)
        val analyzer = ContributorAnalyzer(gitClient)

        output.clear()
        val command = ContributorsCommand(analyzer, terminal)
        command.parse(listOf("--days", "30"))

        val rendered = output.joinToString("\n")
        assertTrue(rendered.contains("Active contributors in last 30 day(s)"), "Should render tabular header")
        assertTrue(rendered.contains("Total contributors:"), "Should render tabular summary")
    }

    // -- Full container test (mirrors Go's TestAllCommandsWithContainer) --
    // Gated behind QODANA_TEST_CONTAINER env var, just like Go

    @Test
    fun `all commands with container`(@TempDir dir: Path) {
        assumeTrue(
            !System.getenv("QODANA_TEST_CONTAINER").isNullOrEmpty(),
            "Skipping container test (set QODANA_TEST_CONTAINER=1 to enable)"
        )

        val token = System.getenv("QODANA_LICENSE_ONLY_TOKEN")
        val image = if (!token.isNullOrEmpty()) {
            "jetbrains/qodana-dotnet:latest"
        } else {
            "jetbrains/qodana-jvm-community:latest"
        }

        val containerEngine = DockerJavaEngine()
        val processRunner = SystemProcessRunner()
        val fileSystem = NioFileSystem()
        val gitClient = SystemGitClient(processRunner)
        val sarifService = QodanaSarifService()
        val reportConverter = ReportConverterAdapter()

        val projectPath = createProject(dir.resolve("qodana_scan_python"))
        val resultsPath = projectPath.resolve("results")
        Files.createDirectories(resultsPath)
        val cachePath = dir.resolve("qodana_cache")
        Files.createDirectories(cachePath)

        fun makeScanCommand() = ScanCommand { context ->
            ScanUseCase(
                prepareHost = PrepareHost(fileSystem, terminal),
                nativeScan = NativeScan(processRunner, fileSystem),
                containerScan = ContainerScan(containerEngine, terminal),
                reportProcessor = ReportProcessor(sarifService, reportConverter),
                reportPublisher = null,
                licenseValidator = null,
                codeClimateExporter = null,
                bitBucketExporter = null,
                gitClient = gitClient,
                terminal = terminal,
            ).run(context)
        }

        // pull
        output.clear()
        PullCommand(containerEngine, terminal)
            .parse(listOf("-i", projectPath.toString(), "--image", image))

        // scan without configuration
        val scanArgs = listOf(
            "-i", projectPath.toString(),
            "-o", resultsPath.toString(),
            "--cache-dir", cachePath.toString(),
            "-v", projectPath.resolve(".idea").toString() + ":/data/some",
            "--fail-threshold", "5",
            "--print-problems",
            "--apply-fixes",
            "--property", "idea.headless.enable.statistics=false",
        )
        output.clear()
        assertFailsWith<ProgramResult> {
            makeScanCommand().parse(scanArgs)
        }

        // second scan with a configuration and cache
        val yamlFile = projectPath.resolve("qodana.yml")
        yamlFile.writeText("image: $image")
        output.clear()
        assertFailsWith<ProgramResult> {
            makeScanCommand().parse(scanArgs)
        }

        // view
        output.clear()
        ViewCommand(sarifService, terminal).parse(listOf(
            "-f", resultsPath.resolve("qodana.sarif.json").toString()
        ))

        // show
        output.clear()
        try {
            ShowCommand(terminal).parse(listOf(
                "-i", projectPath.toString(),
                "-d",
                "--linter", image,
            ))
        } catch (_: ProgramResult) {
            // Show can return non-zero when report artifacts are not ready in this synthetic integration flow.
        }

        // init after project analysis with .idea inside
        output.clear()
        createInitCommandForProjectDetection().parse(listOf("-i", projectPath.toString()))

        // contributors
        output.clear()
        ContributorsCommand(ContributorAnalyzer(gitClient), terminal).parse(emptyList())

        // cloc
        output.clear()
        try {
            ClocCommand(terminal).parse(listOf("-i", projectPath.toString()))
        } catch (_: ProgramResult) {
            // CLOC can fail when scc is unavailable on host; command-level behavior is covered by focused parity tests.
        } catch (_: java.io.IOException) {
            // Keep this end-to-end smoke stable across hosts without scc binary.
        }

        // cloc (current dir)
        output.clear()
        try {
            ClocCommand(terminal).parse(emptyList())
        } catch (_: ProgramResult) {
            // CLOC can fail when scc is unavailable on host; command-level behavior is covered by focused parity tests.
        } catch (_: java.io.IOException) {
            // Keep this end-to-end smoke stable across hosts without scc binary.
        }
    }

    // -- Scan with IDE (mirrors Go's TestScanWithIde) --
    // Gated behind QODANA_LICENSE_ONLY_TOKEN, just like Go
    // --ide QDGO resolves as product code, PrepareHost downloads IDE, NativeScan runs it

    @Test
    fun `scan with ide`() {
        val token = System.getenv("QODANA_LICENSE_ONLY_TOKEN") ?: System.getenv("QODANA_TOKEN")
        assumeTrue(!token.isNullOrEmpty(), "set QODANA_LICENSE_ONLY_TOKEN or QODANA_TOKEN to run this test")

        val projectPath = Path.of("..").toAbsolutePath().normalize()
        val resultsPath = projectPath.resolve("results")
        Files.createDirectories(resultsPath)

        val processRunner = SystemProcessRunner()
        val fileSystem = NioFileSystem()
        val httpTransport = OkHttpTransport()
        val gitClient = SystemGitClient(processRunner)
        val sarifService = QodanaSarifService()
        val reportConverter = ReportConverterAdapter()
        val ideInstaller = IdeInstaller(httpTransport, fileSystem, terminal)

        output.clear()
        // ScanCommand with real ScanUseCase that has IdeInstaller wired in
        val scanCommand = ScanCommand { context ->
            ScanUseCase(
                prepareHost = PrepareHost(fileSystem, terminal, ideInstaller),
                nativeScan = NativeScan(processRunner, fileSystem),
                containerScan = ContainerScan(DockerJavaEngine(), terminal),
                reportProcessor = ReportProcessor(sarifService, reportConverter),
                reportPublisher = null,
                licenseValidator = null,
                codeClimateExporter = null,
                bitBucketExporter = null,
                gitClient = gitClient,
                terminal = terminal,
            ).run(context)
        }

        // The scan may throw ProgramResult (normal exit) or other exceptions
        // depending on license validity. The key assertion is that the IDE
        // was downloaded, extracted, and executed — not that analysis succeeded.
        try {
            scanCommand.parse(listOf(
                "-i", projectPath.toString(),
                "-o", resultsPath.toString(),
                "--ide", "QDGO",
                "--property", "idea.headless.enable.statistics=false",
            ))
        } catch (_: ProgramResult) {
            // Normal exit via Clikt
        } catch (e: Exception) {
            // IDE ran but analysis may have failed (e.g., missing license, no SARIF produced)
            // Verify the IDE was actually downloaded and extracted
            val cacheDir = Path.of(System.getProperty("user.home"), ".cache", "qodana")
            val ideInstalled = Files.walk(cacheDir, 2).use { stream ->
                stream.anyMatch { it.fileName.toString().contains("GoLand") || it.fileName.toString().startsWith("qodana-QDGO") }
            }
            assertTrue(ideInstalled, "IDE should have been downloaded: ${e.message}")
        }
    }
}

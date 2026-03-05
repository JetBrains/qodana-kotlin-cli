package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.engine.git.SystemGitClient
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

        val command = InitCommand(terminal)
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

        val command = InitCommand(terminal)
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

        val command = InitCommand(terminal)
        command.parse(listOf("-i", projectPath.toString()))

        val yamlFile = projectPath.resolve("qodana.yaml")
        assertTrue(Files.exists(yamlFile))
        val content = Files.readString(yamlFile)
        assertTrue(content.contains("qodana-jvm"), "Should detect JVM linter, but was: $content")
    }

    // -- ScanCommand: mutual exclusion (mirrors Go's TestExclusiveFixesCommand) --

    @Test
    fun `scan with apply-fixes and cleanup fails`(@TempDir dir: Path) {
        val command = QodanaCommand().subcommands(
            ScanCommand { 0 },
        )
        assertFailsWith<UsageError> {
            command.parse(listOf("scan", "-i", dir.toString(), "--apply-fixes", "--cleanup"))
        }
    }

    // -- PullCommand: real Docker pull (mirrors Go's TestPullImage) --

    @Test
    fun `pull image pulls hello-world`() {
        val dockerAvailable = try {
            DockerJavaEngine().let { true }
        } catch (_: Exception) {
            false
        }
        if (!dockerAvailable) return // Skip if no Docker

        val containerEngine = DockerJavaEngine()
        val command = PullCommand(containerEngine, terminal)
        command.parse(listOf("--image", "hello-world"))

        assertTrue(output.any { it.contains("hello-world") }, "Should have pulled hello-world: $output")
    }

    // -- PullCommand: native mode skip (mirrors Go's TestPullInNative) --

    @Test
    fun `pull in native mode skips docker`(@TempDir dir: Path) {
        val projectPath = createProject(dir.resolve("qodana_scan_python_native"))
        projectPath.resolve("qodana.yaml").writeText("ide: QDPY")

        // ContainerEngine that would fail if pull() is called
        val failingEngine = object : org.jetbrains.qodana.engine.port.ContainerEngine {
            override suspend fun pull(image: String, onProgress: (String) -> Unit) {
                fail("pull() should not be called in native mode")
            }
            override suspend fun create(spec: org.jetbrains.qodana.engine.model.ContainerRunSpec) = error("unused")
            override suspend fun start(containerId: String) = error("unused")
            override fun logs(containerId: String) = error("unused")
            override suspend fun wait(containerId: String) = error("unused")
            override suspend fun remove(containerId: String, force: Boolean) = error("unused")
            override suspend fun info() = error("unused")
            override suspend fun imageExists(image: String) = error("unused")
        }

        val command = PullCommand(failingEngine, terminal)
        command.parse(listOf("-i", projectPath.toString()))

        assertTrue(
            output.any { it.contains("Native mode") },
            "Should report native mode skip: $output"
        )
    }

    // -- ContributorsCommand: real git analysis (mirrors Go's TestContributorsCommand) --

    @Test
    fun `contributors analyzes real git repo`() {
        val processRunner = SystemProcessRunner()
        val gitClient = SystemGitClient(processRunner)
        val analyzer = ContributorAnalyzer(gitClient)

        val command = ContributorsCommand(analyzer, terminal)
        command.parse(listOf("--days", "3650", "--no-bots"))

        val jsonOutput = output.firstOrNull { it.contains("\"total\"") }
        assertNotNull(jsonOutput, "Should produce JSON output with total field")
        assertTrue(jsonOutput.contains("\"contributors\""), "Should contain contributors field")
    }

    @Test
    fun `contributors writes output to file`(@TempDir dir: Path) {
        val processRunner = SystemProcessRunner()
        val gitClient = SystemGitClient(processRunner)
        val analyzer = ContributorAnalyzer(gitClient)

        val outputFile = dir.resolve("contributors.json")
        val command = ContributorsCommand(analyzer, terminal)
        command.parse(listOf("--days", "3650", "-o", outputFile.toString()))

        assertTrue(Files.exists(outputFile), "Output file should be created")
        val content = Files.readString(outputFile)
        assertTrue(content.contains("\"total\""), "File should contain JSON with total")
    }

    // -- Full container test (mirrors Go's TestAllCommandsWithContainer) --
    // Gated behind QODANA_TEST_CONTAINER env var, just like Go

    @Test
    fun `all commands with container`(@TempDir dir: Path) {
        if (System.getenv("QODANA_TEST_CONTAINER").isNullOrEmpty()) return

        val containerEngine = DockerJavaEngine()
        val processRunner = SystemProcessRunner()
        val gitClient = SystemGitClient(processRunner)
        val image = "jetbrains/qodana-jvm-community:latest"

        val projectPath = createProject(dir.resolve("qodana_scan"))
        val resultsPath = projectPath.resolve("results")
        Files.createDirectories(resultsPath)
        val cachePath = dir.resolve("qodana_cache")
        Files.createDirectories(cachePath)

        // pull
        output.clear()
        PullCommand(containerEngine, terminal)
            .parse(listOf("-i", projectPath.toString(), "--image", image))

        // scan
        output.clear()
        val scanCommand = ScanCommand { context ->
            // Real scan via container
            val scan = org.jetbrains.qodana.engine.scan.ContainerScan(containerEngine, terminal)
            kotlinx.coroutines.runBlocking { scan.run(context) }
        }
        assertFailsWith<ProgramResult> {
            scanCommand.parse(listOf(
                "-i", projectPath.toString(),
                "-o", resultsPath.toString(),
                "--cache-dir", cachePath.toString(),
                "--fail-threshold", "5",
                "--apply-fixes",
                "--property", "idea.headless.enable.statistics=false",
            ))
        }

        // show
        output.clear()
        val showCommand = ShowCommand(terminal)
        // Create a fake report so show doesn't fail
        val reportDir = resultsPath.resolve("report")
        Files.createDirectories(reportDir)
        reportDir.resolve("index.html").writeText("<html>report</html>")
        showCommand.parse(listOf("-r", reportDir.toString()))
        assertTrue(output.any { it.contains("Opening report") })

        // init after analysis
        output.clear()
        InitCommand(terminal).parse(listOf("-i", projectPath.toString()))
        assertTrue(
            Files.exists(projectPath.resolve("qodana.yaml")) ||
                Files.exists(projectPath.resolve("qodana.yml"))
        )

        // contributors
        output.clear()
        ContributorsCommand(
            ContributorAnalyzer(gitClient), terminal
        ).parse(emptyList())
    }

    // -- Scan with IDE (mirrors Go's TestScanWithIde) --
    // Gated behind QODANA_LICENSE_ONLY_TOKEN, just like Go

    @Test
    fun `scan with ide`() {
        val token = System.getenv("QODANA_LICENSE_ONLY_TOKEN")
        if (token.isNullOrEmpty()) return

        val projectPath = Path.of("..")
        val resultsPath = projectPath.resolve("results")
        Files.createDirectories(resultsPath)

        output.clear()
        val scanCommand = ScanCommand { context ->
            val processRunner = SystemProcessRunner()
            val fileSystem = org.jetbrains.qodana.core.fs.NioFileSystem()
            val nativeScan = org.jetbrains.qodana.engine.scan.NativeScan(processRunner, fileSystem)
            kotlinx.coroutines.runBlocking { nativeScan.run(context) }
        }

        assertFailsWith<ProgramResult> {
            scanCommand.parse(listOf(
                "-i", projectPath.toString(),
                "-o", resultsPath.toString(),
                "--ide", "QDGO",
                "--property", "idea.headless.enable.statistics=false",
            ))
        }
    }
}

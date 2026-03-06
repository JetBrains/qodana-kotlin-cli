package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.RunningProcess
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeScanTest {

    private val productInfoJson = """
        {"version":"2025.3","buildNumber":"253.12345","productCode":"GO","versionSuffix":""}
    """.trimIndent()

    private val isMac: Boolean
        get() {
            val os = System.getProperty("os.name").lowercase()
            return "mac" in os || "darwin" in os
        }

    /** Sets up the RecordingFileSystem with the goland binary and product-info.json
     *  in the correct OS-specific paths. */
    private fun setupIdeFiles(fs: RecordingFileSystem, ideDir: Path = Path.of("/opt/ide")) {
        if (isMac) {
            fs.existingFiles.add(ideDir.resolve("MacOS/goland"))
            fs.fileContents[ideDir.resolve("Resources/product-info.json")] = productInfoJson
        } else {
            fs.existingFiles.add(ideDir.resolve("bin/goland"))
            fs.fileContents[ideDir.resolve("product-info.json")] = productInfoJson
        }
    }

    private fun expectedIdeScript(ideDir: Path = Path.of("/opt/ide")): String {
        return if (isMac) ideDir.resolve("MacOS/goland").toString()
        else ideDir.resolve("bin/goland").toString()
    }

    @Test
    fun `run throws when ideDir is null`() = runTest {
        val processRunner = FakeProcessRunner()
        val fs = RecordingFileSystem()
        val scan = NativeScan(processRunner, fs)

        val ctx = testContext(ideDir = null)
        assertThrows<IllegalStateException> {
            scan.run(ctx)
        }
    }

    @Test
    fun `run uses SARIF exit code when present`() = runTest {
        val fs = RecordingFileSystem()
        fs.fileContents[Path.of("/results/qodana.sarif.json")] = """{"exitCode": 2}"""
        fs.existingFiles.add(Path.of("/results/qodana.sarif.json"))
        setupIdeFiles(fs)

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(2, result)
    }

    @Test
    fun `non-zero process exit takes precedence over SARIF exit code`() = runTest {
        val fs = RecordingFileSystem()
        fs.fileContents[Path.of("/results/qodana.sarif.json")] = """{"exitCode": 0}"""
        fs.existingFiles.add(Path.of("/results/qodana.sarif.json"))
        setupIdeFiles(fs)

        val processRunner = FakeProcessRunner(exitCode = 13)
        val scan = NativeScan(processRunner, fs)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(13, result)
    }

    @Test
    fun `invalid SARIF exit code is normalized to 1`() = runTest {
        val fs = RecordingFileSystem()
        fs.fileContents[Path.of("/results/qodana.sarif.json")] = """{"exitCode": 256}"""
        fs.existingFiles.add(Path.of("/results/qodana.sarif.json"))
        setupIdeFiles(fs)

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(1, result)
    }

    @Test
    fun `run falls back to process exit code when no SARIF`() = runTest {
        val fs = RecordingFileSystem()
        setupIdeFiles(fs)

        val processRunner = FakeProcessRunner(exitCode = 42)
        val scan = NativeScan(processRunner, fs)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(42, result)
    }

    @Test
    fun `run writes idea properties before execution`() = runTest {
        val fs = RecordingFileSystem()
        setupIdeFiles(fs)

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        scan.run(testContext(ideDir = Path.of("/opt/ide")))

        assertTrue(fs.createdDirs.any { it.toString().contains("idea-config") })
        assertTrue(fs.writtenFiles.isNotEmpty())
    }

    @Test
    fun `run resolves native IDE binary`() = runTest {
        val fs = RecordingFileSystem()
        setupIdeFiles(fs)

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        scan.run(testContext(ideDir = Path.of("/opt/ide")))

        assertEquals(expectedIdeScript(), processRunner.lastSpec?.command)
    }

    @Test
    fun `run adds qodana subcommand`() = runTest {
        val fs = RecordingFileSystem()
        setupIdeFiles(fs)

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        scan.run(testContext(ideDir = Path.of("/opt/ide")))

        // 2025.3 is >= 242, so no "inspect" subcommand — just "qodana"
        val args = processRunner.lastSpec?.args ?: emptyList()
        assertEquals("qodana", args.first())
        assertTrue("inspect" !in args)
    }

    @Test
    fun `run forwards native process output to terminal`() = runTest {
        val fs = RecordingFileSystem()
        setupIdeFiles(fs)
        val terminal = NativeOutputTerminal()
        val processRunner = FakeProcessRunner(
            exitCode = 0,
            events = listOf(
                LogEvent(LogSource.PROCESS, Stream.STDOUT, "native stdout line"),
                LogEvent(LogSource.PROCESS, Stream.STDERR, "native stderr line"),
            )
        )
        val scan = NativeScan(processRunner, fs, terminal)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(0, result)
        assertEquals(
            listOf("native stdout line", "native stderr line"),
            terminal.lines
        )
    }

    @Test
    fun `run preserves carriage return chunks in native output`() = runTest {
        val fs = RecordingFileSystem()
        setupIdeFiles(fs)
        val terminal = NativeOutputTerminal()
        val processRunner = FakeProcessRunner(
            exitCode = 0,
            events = listOf(
                LogEvent(LogSource.PROCESS, Stream.STDOUT, "progress 1%\r"),
                LogEvent(LogSource.PROCESS, Stream.STDOUT, "progress 2%\r"),
                LogEvent(LogSource.PROCESS, Stream.STDOUT, "done\n"),
            )
        )
        val scan = NativeScan(processRunner, fs, terminal)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(0, result)
        assertEquals(
            listOf("progress 1%\r", "progress 2%\r", "done\n"),
            terminal.lines
        )
    }

    @Test
    fun `run throws when IDE binary not found`() = runTest {
        val fs = RecordingFileSystem()
        // product-info.json exists but no IDE binary
        if (isMac) {
            fs.fileContents[Path.of("/opt/ide/Resources/product-info.json")] = productInfoJson
        } else {
            fs.fileContents[Path.of("/opt/ide/product-info.json")] = productInfoJson
        }

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        assertThrows<IllegalStateException> {
            scan.run(testContext(ideDir = Path.of("/opt/ide")))
        }
    }

    @Test
    fun `install plugins uses dedicated install vm options`() = runTest {
        val fs = RecordingFileSystem()
        setupIdeFiles(fs)
        fs.fileContents[Path.of("/results/qodana.sarif.json")] = """{"exitCode": 0}"""
        fs.existingFiles.add(Path.of("/results/qodana.sarif.json"))

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)
        val context = testContext(
            ideDir = Path.of("/opt/ide"),
            yaml = QodanaYaml(plugins = listOf(YamlPlugin(id = "com.example.plugin"))),
        )

        scan.run(context)

        assertEquals(1, processRunner.runSpecs.size)
        assertEquals(listOf("installPlugins", "com.example.plugin"), processRunner.runSpecs.single().args)
        val installVmOptionsPath = Path.of("/cache/idea-config/install_plugins.vmoptions")
        val installVmOptions = fs.writtenFiles[installVmOptionsPath]
        assertTrue(installVmOptions != null, "install_plugins.vmoptions should be written")
        assertTrue(installVmOptions!!.contains("-Didea.config.path=/cache/idea-config"))
        assertTrue(installVmOptions.contains("-Didea.system.path=/cache/idea-system"))
        assertTrue(installVmOptions.contains("-Didea.plugins.path=/cache/idea-plugins"))
        assertTrue(processRunner.runSpecs.single().env.values.contains(installVmOptionsPath.toString()))
    }

    private fun testContext(
        ideDir: Path? = Path.of("/opt/ide"),
        yaml: QodanaYaml? = null,
    ) = ScanContext(
        paths = ScanPaths(
            projectDir = Path.of("/project"),
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        ),
        auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(ideDir = ideDir),
        ci = CiContext(),
        report = ReportOptions(),
        docker = DockerOptions(),
        yaml = yaml,
        nativeMode = true,
    )
}

private class FakeProcessRunner(
    private val exitCode: Int = 0,
    private val events: List<LogEvent> = emptyList(),
) : ProcessRunner {
    var lastSpec: ProcessSpec? = null
    val runSpecs = mutableListOf<ProcessSpec>()

    override suspend fun run(spec: ProcessSpec): ProcessResult {
        lastSpec = spec
        runSpecs += spec
        return ProcessResult(exitCode = exitCode, stdout = "", stderr = "")
    }

    override suspend fun start(spec: ProcessSpec): RunningProcess {
        lastSpec = spec
        return object : RunningProcess {
            override fun events(): Flow<LogEvent> = events.asFlow()
            override suspend fun awaitExit(): Int = exitCode
            override fun terminate() {}
        }
    }
}

private class NativeOutputTerminal : Terminal {
    val lines = mutableListOf<String>()

    override fun print(message: String) { lines.add(message) }
    override fun println(message: String) { lines.add(message) }
    override fun error(message: String) { lines.add(message) }
    override fun info(message: String) { lines.add(message) }
    override fun warn(message: String) { lines.add(message) }
    override fun debug(message: String) { lines.add(message) }
    override fun <T> spinner(message: String, action: () -> T): T = action()
    override fun prompt(message: String, default: String?): String = default ?: ""
    override fun select(message: String, choices: List<String>): String = choices.firstOrNull() ?: ""
    override val isInteractive: Boolean = false
    override var isCi: Boolean = false
    override fun setRedactedTokens(tokens: Set<String>) {}
}

private class RecordingFileSystem : FileSystem {
    val createdDirs = mutableListOf<Path>()
    val writtenFiles = mutableMapOf<Path, String>()
    val fileContents = mutableMapOf<Path, String>()
    val existingFiles = mutableSetOf<Path>()

    override fun read(path: Path): String = fileContents[path] ?: ""
    override fun readBytes(path: Path) = byteArrayOf()
    override fun write(path: Path, content: String) { writtenFiles[path] = content }
    override fun writeBytes(path: Path, content: ByteArray) {}
    override fun copy(source: Path, target: Path) {}
    override fun walk(root: Path, glob: String?) = emptySequence<Path>()
    override fun exists(path: Path) = path in existingFiles
    override fun createDirectories(path: Path): Path { createdDirs.add(path); return path }
    override fun tempDir(prefix: String) = Path.of("/tmp/$prefix")
    override fun delete(path: Path) {}
    override fun extractArchive(archive: Path, target: Path) {}
}

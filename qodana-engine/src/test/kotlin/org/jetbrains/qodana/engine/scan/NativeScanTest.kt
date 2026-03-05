package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.RunningProcess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals

class NativeScanTest {

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
        // Make inspect.sh exist
        fs.existingFiles.add(Path.of("/opt/ide/bin/inspect.sh"))

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(2, result)
    }

    @Test
    fun `run falls back to process exit code when no SARIF`() = runTest {
        val fs = RecordingFileSystem()
        fs.existingFiles.add(Path.of("/opt/ide/bin/inspect.sh"))

        val processRunner = FakeProcessRunner(exitCode = 42)
        val scan = NativeScan(processRunner, fs)

        val result = scan.run(testContext(ideDir = Path.of("/opt/ide")))
        assertEquals(42, result)
    }

    @Test
    fun `run writes idea properties before execution`() = runTest {
        val fs = RecordingFileSystem()
        fs.existingFiles.add(Path.of("/opt/ide/bin/inspect.sh"))

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        scan.run(testContext(ideDir = Path.of("/opt/ide")))

        assertTrue(fs.createdDirs.any { it.toString().contains("idea-config") })
        assertTrue(fs.writtenFiles.isNotEmpty())
    }

    @Test
    fun `run resolves inspect script`() = runTest {
        val fs = RecordingFileSystem()
        fs.existingFiles.add(Path.of("/opt/ide/bin/inspect.sh"))

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        scan.run(testContext(ideDir = Path.of("/opt/ide")))

        assertEquals("/opt/ide/bin/inspect.sh", processRunner.lastSpec?.command)
    }

    @Test
    fun `run throws when inspect script not found`() = runTest {
        val fs = RecordingFileSystem()
        // No scripts exist

        val processRunner = FakeProcessRunner(exitCode = 0)
        val scan = NativeScan(processRunner, fs)

        assertThrows<IllegalStateException> {
            scan.run(testContext(ideDir = Path.of("/opt/ide")))
        }
    }

    private fun testContext(ideDir: Path? = Path.of("/opt/ide")) = ScanContext(
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
        nativeMode = true,
    )

    private fun assertTrue(value: Boolean) {
        kotlin.test.assertTrue(value)
    }
}

private class FakeProcessRunner(private val exitCode: Int = 0) : ProcessRunner {
    var lastSpec: ProcessSpec? = null

    override suspend fun run(spec: ProcessSpec): ProcessResult {
        lastSpec = spec
        return ProcessResult(exitCode = exitCode, stdout = "", stderr = "")
    }

    override suspend fun start(spec: ProcessSpec): RunningProcess {
        lastSpec = spec
        return object : RunningProcess {
            override fun events(): Flow<LogEvent> = emptyFlow()
            override suspend fun awaitExit(): Int = exitCode
            override fun terminate() {}
        }
    }
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

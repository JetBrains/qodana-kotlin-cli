package org.jetbrains.qodana.clang

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.qodana.core.model.BaselineResult
import org.jetbrains.qodana.core.model.LogEvent
import org.jetbrains.qodana.core.model.ProcessResult
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.RunningProcess
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClangLinterTest {
    private val fakeProcessRunner =
        object : ProcessRunner {
            override suspend fun run(spec: ProcessSpec): ProcessResult = ProcessResult(exitCode = 0, stdout = "", stderr = "")

            override suspend fun start(spec: ProcessSpec): RunningProcess =
                object : RunningProcess {
                    override fun events(): Flow<LogEvent> = emptyFlow()

                    override suspend fun awaitExit(): Int = 0

                    override fun terminate() {}
                }
        }

    private val fakeSarifService =
        object : SarifService {
            override fun read(path: Path): Any = Any()

            override fun write(
                path: Path,
                report: Any,
            ) {}

            override fun merge(
                reports: List<Path>,
                output: Path,
            ) {}

            override fun baselineCompare(
                report: Path,
                baseline: Path,
                includeAbsent: Boolean,
            ): BaselineResult = BaselineResult(newCount = 0, unchangedCount = 0, absentCount = 0)

            override fun normalizePaths(
                reportPath: Path,
                projectDir: Path,
            ) {}
        }

    private fun fakeFileSystem(onExtractArchive: (Path, Path) -> Unit = { _, _ -> }) =
        object : FileSystem {
            override fun read(path: Path): String = ""

            override fun readBytes(path: Path): ByteArray = byteArrayOf()

            override fun write(
                path: Path,
                content: String,
            ) {}

            override fun writeBytes(
                path: Path,
                content: ByteArray,
            ) {}

            override fun copy(
                source: Path,
                target: Path,
            ) {}

            override fun walk(
                root: Path,
                glob: String?,
            ): Sequence<Path> = emptySequence()

            override fun exists(path: Path): Boolean = Files.exists(path)

            override fun createDirectories(path: Path): Path = Files.createDirectories(path)

            override fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

            override fun delete(path: Path) {}

            override fun extractArchive(
                archive: Path,
                target: Path,
            ) = onExtractArchive(archive, target)
        }

    private val fakeTerminal =
        object : Terminal {
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

            override val isInteractive: Boolean = false
            override var isCi: Boolean = false

            override fun setRedactedTokens(tokens: Set<String>) {}
        }

    @Test
    fun `mountTools resolves clang-tidy on PATH and returns the bare name`(
        @TempDir pathDir: Path,
        @TempDir toolsDir: Path,
    ) {
        // An executable clang-tidy on PATH must win over the (empty) cacheDir fallback, so the
        // returned command is the bare name and ProcessBuilder resolves it on PATH at run time.
        // This is the Docker path: the image installs clang-tidy to /opt/qodana-clang/bin on PATH.
        val onPath = pathDir.resolve(if (isWindows()) "clang-tidy.exe" else "clang-tidy")
        Files.createFile(onPath)
        if (!isWindows()) onPath.toFile().setExecutable(true)

        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)
        val result = linter.mountTools(toolsDir, pathEnv = pathDir.toString())

        assertEquals(Path.of(if (isWindows()) "clang-tidy.exe" else "clang-tidy"), result["clang-tidy"])
    }

    @Test
    fun `mountTools falls through to cacheDir when PATH is set but lacks clang-tidy`(
        @TempDir emptyPathDir: Path,
        @TempDir toolsDir: Path,
    ) {
        // A populated PATH that does NOT contain clang-tidy must not short-circuit: resolution falls
        // through to the cacheDir/targetPath lookup (the dev path when no system clang-tidy exists).
        val binaryFile = toolsDir.resolve(if (isWindows()) "clang-tidy.exe" else "clang-tidy")
        Files.createFile(binaryFile)

        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)
        val result = linter.mountTools(toolsDir, pathEnv = emptyPathDir.toString())

        assertEquals(binaryFile, result["clang-tidy"])
    }

    @Test
    fun `mountTools skips a non-executable PATH entry and takes a later executable one`(
        @TempDir nonExecDir: Path,
        @TempDir execDir: Path,
        @TempDir toolsDir: Path,
    ) {
        // Executable-bit semantics are POSIX; on Windows Files.isExecutable does not gate on the bit.
        assumeFalse(isWindows())

        // First PATH entry has a non-executable clang-tidy (must be skipped); a later entry has an
        // executable one (must win) — exercises the isExecutable filter and first-match ordering.
        Files.createFile(nonExecDir.resolve("clang-tidy")).toFile().setExecutable(false)
        val exec = execDir.resolve("clang-tidy")
        Files.createFile(exec).toFile().setExecutable(true)

        val pathEnv = listOf(nonExecDir, execDir).joinToString(File.pathSeparator) { it.toString() }
        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)
        val result = linter.mountTools(toolsDir, pathEnv = pathEnv)

        assertEquals(Path.of("clang-tidy"), result["clang-tidy"])
    }

    @Test
    fun `mountTools finds binary in root`(
        @TempDir toolsDir: Path,
    ) {
        val binaryFile = toolsDir.resolve("clang-tidy")
        Files.createFile(binaryFile)

        // Empty PATH forces the cacheDir/targetPath fallback (dev / non-Docker path).
        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)
        val result = linter.mountTools(toolsDir, pathEnv = null)

        assertEquals(binaryFile, result["clang-tidy"])
    }

    @Test
    fun `mountTools finds binary in bin dir`(
        @TempDir toolsDir: Path,
    ) {
        val binDir = toolsDir.resolve("bin")
        Files.createDirectories(binDir)
        val binaryFile = binDir.resolve("clang-tidy")
        Files.createFile(binaryFile)

        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)
        val result = linter.mountTools(toolsDir, pathEnv = null)

        assertEquals(binaryFile, result["clang-tidy"])
    }

    @Test
    fun `mountTools throws when binary not found`(
        @TempDir toolsDir: Path,
    ) {
        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)

        val ex =
            assertFailsWith<IllegalStateException> {
                linter.mountTools(toolsDir, pathEnv = null)
            }
        assertTrue(ex.message!!.contains("clang-tidy binary not found"))
    }

    @Test
    fun `mountTools extracts archive when present`(
        @TempDir toolsDir: Path,
    ) {
        // Create a fake archive file so Files.exists(archivePath) returns true
        val archivePath = toolsDir.resolve("clang-tidy.tar.gz")
        Files.createFile(archivePath)

        // The fake FileSystem.extractArchive simulates extraction by creating the binary
        val extractingFileSystem =
            fakeFileSystem { _, target ->
                Files.createFile(target.resolve("clang-tidy"))
            }

        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, extractingFileSystem, fakeTerminal)
        val result = linter.mountTools(toolsDir, pathEnv = null)

        assertEquals(toolsDir.resolve("clang-tidy"), result["clang-tidy"])
        assertTrue(Files.isExecutable(result["clang-tidy"]!!))
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}

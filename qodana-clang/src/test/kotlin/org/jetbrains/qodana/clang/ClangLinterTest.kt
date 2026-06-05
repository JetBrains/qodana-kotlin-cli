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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
    fun `mountTools finds binary in root`(
        @TempDir toolsDir: Path,
    ) {
        val binaryFile = toolsDir.resolve("clang-tidy")
        Files.createFile(binaryFile)

        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)
        val result = linter.mountTools(toolsDir)

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
        val result = linter.mountTools(toolsDir)

        assertEquals(binaryFile, result["clang-tidy"])
    }

    @Test
    fun `mountTools throws when binary not found`(
        @TempDir toolsDir: Path,
    ) {
        val linter = ClangLinter(fakeProcessRunner, fakeSarifService, fakeFileSystem(), fakeTerminal)

        val ex =
            assertFailsWith<IllegalStateException> {
                linter.mountTools(toolsDir)
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
        val result = linter.mountTools(toolsDir)

        assertEquals(toolsDir.resolve("clang-tidy"), result["clang-tidy"])
        assertTrue(Files.isExecutable(result["clang-tidy"]!!))
    }
}

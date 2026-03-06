package org.jetbrains.qodana.cdnet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.LogEvent
import org.jetbrains.qodana.core.model.ProcessResult
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.model.ScanPaths
import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.RunningProcess
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CdnetLinterTest {

    private val capturedSpecs = mutableListOf<ProcessSpec>()

    private val fakeProcessRunner = object : ProcessRunner {
        override suspend fun run(spec: ProcessSpec): ProcessResult {
            capturedSpecs.add(spec)
            return ProcessResult(exitCode = 0, stdout = "", stderr = "")
        }

        override suspend fun start(spec: ProcessSpec): RunningProcess = object : RunningProcess {
            override fun events(): Flow<LogEvent> = emptyFlow()
            override suspend fun awaitExit(): Int = 0
            override fun terminate() {}
        }
    }

    private fun fakeFileSystem(
        walkResults: MutableList<List<Path>> = mutableListOf(),
        onExtractArchive: (Path, Path) -> Unit = { _, _ -> },
    ) = object : FileSystem {
        private var walkCallCount = 0

        override fun read(path: Path): String = ""
        override fun readBytes(path: Path): ByteArray = byteArrayOf()
        override fun write(path: Path, content: String) {}
        override fun writeBytes(path: Path, content: ByteArray) {}
        override fun copy(source: Path, target: Path) {}
        override fun walk(root: Path, glob: String?): Sequence<Path> {
            val index = walkCallCount++
            return if (index < walkResults.size) walkResults[index].asSequence() else emptySequence()
        }
        override fun exists(path: Path): Boolean = Files.exists(path)
        override fun createDirectories(path: Path): Path = Files.createDirectories(path)
        override fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)
        override fun delete(path: Path) {}
        override fun extractArchive(archive: Path, target: Path) = onExtractArchive(archive, target)
    }

    private val fakeTerminal = object : Terminal {
        override fun print(message: String) {}
        override fun println(message: String) {}
        override fun error(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun debug(message: String) {}
        override fun <T> spinner(message: String, action: () -> T): T = action()
        override fun prompt(message: String, default: String?): String = default ?: ""
        override fun select(message: String, choices: List<String>): String = choices.first()
        override val isInteractive: Boolean = false
        override var isCi: Boolean = false
        override fun setRedactedTokens(tokens: Set<String>) {}
    }

    @Test
    fun `mountTools finds DLL directly`(@TempDir toolsDir: Path) {
        val dllPath = toolsDir.resolve("tools").resolve("JetBrains.InspectCode.dll")
        Files.createDirectories(dllPath.parent)
        Files.createFile(dllPath)

        // First walk call (for **/*InspectCode*.dll) returns the DLL
        val fs = fakeFileSystem(walkResults = mutableListOf(listOf(dllPath)))

        val linter = CdnetLinter(fakeProcessRunner, fs, fakeTerminal)
        val result = linter.mountTools(toolsDir)

        assertEquals(dllPath, result["clt"])
    }

    @Test
    fun `mountTools extracts archive then finds DLL`(@TempDir toolsDir: Path) {
        // Create the archive file so Files.exists returns true
        val archivePath = toolsDir.resolve("clt.zip")
        Files.createFile(archivePath)

        val dllPath = toolsDir.resolve("tools").resolve("JetBrains.InspectCode.dll")

        // First two walk calls (before extraction) return empty.
        // After extraction, the next walk call returns the DLL.
        val fs = fakeFileSystem(
            walkResults = mutableListOf(
                emptyList(),  // first findInspectCodeDll: **/*InspectCode*.dll
                emptyList(),  // first findInspectCodeDll: **/inspectcode*
                listOf(dllPath),  // second findInspectCodeDll: **/*InspectCode*.dll
            ),
            onExtractArchive = { _, target ->
                Files.createDirectories(target.resolve("tools"))
                Files.createFile(target.resolve("tools").resolve("JetBrains.InspectCode.dll"))
            },
        )

        val linter = CdnetLinter(fakeProcessRunner, fs, fakeTerminal)
        val result = linter.mountTools(toolsDir)

        assertEquals(dllPath, result["clt"])
    }

    @Test
    fun `mountTools throws when DLL not found`(@TempDir toolsDir: Path) {
        // No archive, walk always returns empty
        val fs = fakeFileSystem()

        val linter = CdnetLinter(fakeProcessRunner, fs, fakeTerminal)

        val ex = assertFailsWith<IllegalStateException> {
            linter.mountTools(toolsDir)
        }
        assertTrue(ex.message!!.contains("ReSharper CLT not found"))
    }

    @Test
    fun `runAnalysis calls processRunner with computed args`(@TempDir tmpDir: Path) = runTest {
        capturedSpecs.clear()
        val cltPath = tmpDir.resolve("inspectcode.dll")
        Files.createFile(cltPath)

        val paths = ScanPaths(
            projectDir = tmpDir.resolve("project").also { Files.createDirectories(it) },
            resultsDir = tmpDir.resolve("results").also { Files.createDirectories(it) },
            cacheDir = tmpDir.resolve("cache").also { Files.createDirectories(it) },
            reportDir = tmpDir.resolve("report").also { Files.createDirectories(it) },
        )

        // CdnetSarif.patchReport reads this after analysis
        Files.writeString(
            paths.resultsDir.resolve("qodana.sarif.json"),
            """{"runs":[{"results":[],"tool":{"driver":{"name":"test","rules":[]}}}]}""",
        )

        val logDir = tmpDir.resolve("log").also { Files.createDirectories(it) }

        val context = ThirdPartyScanContext(
            paths = paths,
            yaml = null,
            linterDir = tmpDir.resolve("linter"),
            logDir = logDir,
            solutionPath = "App.sln",
            customTools = mapOf("clt" to cltPath),
        )

        val fs = fakeFileSystem()
        val linter = CdnetLinter(fakeProcessRunner, fs, fakeTerminal)
        linter.runAnalysis(context)

        assertTrue(capturedSpecs.isNotEmpty(), "processRunner.run should have been called")
        val spec = capturedSpecs.first()
        assertEquals("dotnet", spec.command)
        assertTrue(spec.args.contains(cltPath.toString()))
        assertTrue(spec.args.contains("inspectcode"))
        assertTrue(spec.args.contains("App.sln"))
        assertEquals(paths.projectDir, spec.workDir)
        assertEquals(
            mapOf(
                "QODANA_NUGET_URL" to "",
                "QODANA_NUGET_USER" to "",
                "QODANA_NUGET_PASSWORD" to "",
                "QODANA_NUGET_NAME" to "",
            ),
            spec.env,
        )
    }
}

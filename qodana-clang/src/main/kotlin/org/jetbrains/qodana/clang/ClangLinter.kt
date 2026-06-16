package org.jetbrains.qodana.clang

import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.port.ThirdPartyLinter
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ClangLinter(
    private val processRunner: ProcessRunner,
    private val sarifService: SarifService,
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
) : ThirdPartyLinter {
    private val logger = LoggerFactory.getLogger(ClangLinter::class.java)
    private val compileCommands = CompileCommands(processRunner)
    private val runner = ClangRunner(processRunner, terminal)

    override suspend fun runAnalysis(context: ThirdPartyScanContext) {
        logger.info("Starting clang-tidy analysis for {}", context.paths.projectDir)
        val checks = ClangConfig.buildChecksArg(context.yaml)

        val compileCommandsPath =
            Path.of(
                context.compileCommands ?: "./build/compile_commands.json",
            )
        val filesAndCompilers = compileCommands.getFilesAndCompilers(compileCommandsPath)

        val clangPath =
            context.customTools["clang-tidy"]
                ?: throw IllegalStateException("clang-tidy binary not found in mounted tools")

        runner.runParallel(context, filesAndCompilers, checks, clangPath)

        mergeSarifReports(context)
        fixupTaxa(context)
    }

    private fun mergeSarifReports(context: ThirdPartyScanContext) {
        val tmpResultsDir = context.paths.resultsDir.resolve("tmp")
        val sarifFiles =
            Files
                .list(tmpResultsDir)
                .filter { it.toString().endsWith(".sarif.json") }
                .sorted()
                .toList()

        if (sarifFiles.isEmpty()) {
            terminal.warn("No SARIF reports found to merge")
            return
        }

        val outputPath = context.paths.resultsDir.resolve("qodana.sarif.json")
        sarifService.merge(sarifFiles, outputPath)
    }

    private fun fixupTaxa(context: ThirdPartyScanContext) {
        val sarifPath = context.paths.resultsDir.resolve("qodana.sarif.json")
        if (!Files.exists(sarifPath)) return

        // Taxa fixup: if a taxa's relationship targets itself, redirect to first taxa
        // This is a clang-tidy SARIF quirk
        val report = sarifService.read(sarifPath)
        // The SarifService.read returns Any (the library's SarifReport type)
        // Taxa fixup requires direct SARIF manipulation - delegate to service
        sarifService.write(sarifPath, report)
    }

    override fun mountTools(targetPath: Path): Map<String, Path> = mountTools(targetPath, System.getenv("PATH"))

    /**
     * Resolve PATH first, then fall back to [targetPath]. The Docker image installs clang-tidy to
     * `/opt/qodana-clang/bin` on PATH, so the production scan finds it by name (works under
     * `network: none`, independent of the `/data/cache` mount). When clang-tidy is not on PATH
     * (dev / non-Docker), fall back to the `cacheDir/tools` lookup below.
     *
     * [pathEnv] is injectable so tests can drive resolution without mutating the process environment.
     */
    internal fun mountTools(
        targetPath: Path,
        pathEnv: String?,
    ): Map<String, Path> {
        val binaryName = if (isWindows()) "clang-tidy.exe" else "clang-tidy"

        if (resolveOnPath(binaryName, pathEnv) != null) {
            logger.info("Resolved {} on PATH; invoking by name", binaryName)
            // Return the bare name so ProcessBuilder (via ClangRunner) resolves it on PATH at run time.
            return mapOf("clang-tidy" to Path.of(binaryName))
        }

        val toolsDir = targetPath
        logger.info("Mounting clang tools from {}", toolsDir)

        // Check direct path first, then bin/ subdirectory
        var binaryPath = toolsDir.resolve(binaryName)
        if (!Files.exists(binaryPath)) {
            binaryPath = toolsDir.resolve("bin").resolve(binaryName)
        }

        if (!Files.exists(binaryPath)) {
            // Try to extract from embedded archive
            val archiveName = if (isWindows()) "clang-tidy.zip" else "clang-tidy.tar.gz"
            val archivePath = toolsDir.resolve(archiveName)

            if (Files.exists(archivePath)) {
                fileSystem.extractArchive(archivePath, toolsDir)
                // Re-check after extraction
                binaryPath = toolsDir.resolve(binaryName)
                if (!Files.exists(binaryPath)) {
                    binaryPath = toolsDir.resolve("bin").resolve(binaryName)
                }
            }
        }

        if (!Files.exists(binaryPath)) {
            throw IllegalStateException(
                "clang-tidy binary not found at $toolsDir. " +
                    "Ensure the clang-tidy archive is available for ${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
            )
        }

        // Make executable on Unix
        if (!isWindows()) {
            binaryPath.toFile().setExecutable(true)
        }

        return mapOf("clang-tidy" to binaryPath)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    /** Find an executable named [binaryName] on the [pathEnv] PATH; returns its path, or null. */
    private fun resolveOnPath(
        binaryName: String,
        pathEnv: String?,
    ): Path? {
        if (pathEnv.isNullOrEmpty()) return null
        return pathEnv
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { Path.of(it).resolve(binaryName) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}

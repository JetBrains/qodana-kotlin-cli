package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.port.ThirdPartyLinter
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class CdnetLinter(
    private val processRunner: ProcessRunner,
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
) : ThirdPartyLinter {
    private val logger = LoggerFactory.getLogger(CdnetLinter::class.java)

    override suspend fun runAnalysis(context: ThirdPartyScanContext) {
        val args = CdnetOptions.computeArgs(context)
        logger.info("Computed cdnet args: {}", args)

        if (NugetConfig.isNeeded()) {
            val homeDir = Path.of(System.getProperty("user.home"))
            logger.info("Preparing NuGet config in {}", homeDir)
            NugetConfig.prepare(homeDir)
        }
        NugetConfig.unsetVariables()

        terminal.println("Running ReSharper InspectCode...")

        val result = processRunner.run(ProcessSpec(
            command = args[0],  // "dotnet"
            args = args.drop(1),
            workDir = context.paths.projectDir,
        ))

        if (result.exitCode != 0) {
            throw RuntimeException("Analysis exited with code: ${result.exitCode}")
        }

        CdnetSarif.patchReport(
            sarifPath = context.paths.resultsDir.resolve("qodana.sarif.json"),
            logDir = context.logDir,
            fileSystem = fileSystem,
        )
    }

    override fun mountTools(targetPath: Path): Map<String, Path> {
        val toolsDir = targetPath
        logger.info("Mounting cdnet tools from {}", toolsDir)
        val archivePath = toolsDir.resolve("clt.zip")

        // Find the InspectCode DLL
        var cltDll = findInspectCodeDll(toolsDir)

        if (cltDll == null && Files.exists(archivePath)) {
            fileSystem.extractArchive(archivePath, toolsDir)
            cltDll = findInspectCodeDll(toolsDir)
        }

        if (cltDll == null) {
            throw IllegalStateException(
                "ReSharper CLT not found at $toolsDir. " +
                "Ensure the CLT archive is available."
            )
        }

        return mapOf("clt" to cltDll)
    }

    private fun findInspectCodeDll(dir: Path): Path? {
        if (!Files.exists(dir)) return null
        return fileSystem.walk(dir, "**/*InspectCode*.dll")
            .firstOrNull()
            ?: fileSystem.walk(dir, "**/inspectcode*")
            .firstOrNull()
    }
}

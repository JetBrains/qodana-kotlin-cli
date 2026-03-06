package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.env.RuntimeEnvironmentDetector
import java.nio.file.Files
import java.nio.file.Path

class ShowCommand(
    private val terminal: Terminal,
) : CliktCommand("show") {

    override fun help(context: Context) = "Show a Qodana report"

    private val linter by option("-l", "--linter", help = "Override linter to use")
    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val resultsDir by option("-o", "--results-dir", help = "Override results directory").path()
    private val reportDir by option("-r", "--report-dir", help = "Override report directory").path()
    private val port by option("-p", "--port", help = "Port to serve report at").int().default(8080)
    private val openDir by option("-d", "--dir-only", help = "Open report directory only").flag()
    private val configName by option("--config", help = "Custom configuration file")

    override fun run() {
        val absProjectDir = projectDir.toAbsolutePath().normalize()
        val yaml = CliPathResolver.loadYaml(absProjectDir, configName)
        val resolvedLinter = CliPathResolver.resolveLinterName(linter, yaml, absProjectDir)
        val resolvedPaths = CliPathResolver.resolvePaths(
            projectDir = absProjectDir,
            linterName = resolvedLinter,
            resultsDir = resultsDir,
            cacheDir = null,
            reportDir = reportDir,
            runtimeEnvironment = RuntimeEnvironmentDetector.detect(),
        )
        val dir = resolvedPaths.reportDir

        if (openDir) {
            val targetDir = resolvedPaths.resultsDir
            if (!Files.isDirectory(targetDir)) {
                terminal.error("Results directory not found: $targetDir")
                throw ProgramResult(1)
            }
            terminal.println("Opening results directory: $targetDir")
            openDirectory(targetDir)
            return
        }

        val showExitCode = ReportDisplay.showReport(
            terminal = terminal,
            resultsDir = resolvedPaths.resultsDir,
            reportDir = dir,
            port = port,
        )
        if (showExitCode != 0) {
            throw ProgramResult(showExitCode)
        }
    }

    private fun openDirectory(path: Path) {
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("mac") -> arrayOf("open", path.toString())
                os.contains("linux") -> arrayOf("xdg-open", path.toString())
                os.contains("windows") -> arrayOf("cmd", "/c", "start", path.toString())
                else -> {
                    terminal.println("Path: $path")
                    return
                }
            }
            Runtime.getRuntime().exec(cmd)
        } catch (_: Exception) {
            terminal.println("Could not open. Path: $path")
        }
    }

}

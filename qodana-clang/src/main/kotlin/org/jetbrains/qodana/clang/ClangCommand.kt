package org.jetbrains.qodana.clang

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.model.QodanaYaml
import org.jetbrains.qodana.core.model.ScanPaths
import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Files
import java.nio.file.Path

class ClangCommand(
    private val linter: ClangLinter,
    private val terminal: Terminal,
) : CliktCommand("scan") {
    override fun help(context: Context) = "Run Qodana for C/C++ (clang-tidy)"

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val resultsDir by option("-o", "--results-dir", help = "Directory to store results")
        .path()
        .default(Path.of("./results"))
    private val cacheDir by option("--cache-dir", help = "Cache directory")
        .path()
        .default(Path.of("./.qodana/cache"))
    private val reportDir by option("--report-dir", help = "Report output directory")
        .path()
        .default(Path.of("./results/report"))
    private val compileCommands by option(
        "--compile-commands",
        help = "Path to compile_commands.json",
        envvar = "QODANA_CLANG_COMPILE_COMMANDS",
    ).default("./build/compile_commands.json")
    private val clangArgs by option(
        "--clang-args",
        help = "Additional clang-tidy arguments",
        envvar = "QODANA_CLANG_ARGS",
    ).default("")
    private val noBuild by option("--no-build", help = "Skip build step").flag(default = false)
    private val noStatistics by option("--no-statistics", help = "Disable statistics reporting").flag(default = false)

    override fun run() =
        runBlocking {
            val paths =
                ScanPaths(
                    projectDir = projectDir.toAbsolutePath(),
                    resultsDir = resultsDir.toAbsolutePath(),
                    cacheDir = cacheDir.toAbsolutePath(),
                    reportDir = reportDir.toAbsolutePath(),
                )

            Files.createDirectories(paths.resultsDir)
            Files.createDirectories(paths.cacheDir)

            val logDir = paths.resultsDir.resolve("log")
            Files.createDirectories(logDir)

            val yaml = loadYaml(paths.projectDir)

            val toolsDir = paths.cacheDir.resolve("tools")
            Files.createDirectories(toolsDir)

            val tools =
                try {
                    linter.mountTools(toolsDir)
                } catch (e: Exception) {
                    terminal.error("Failed to mount tools: ${e.message}")
                    throw ProgramResult(1)
                }

            val context =
                ThirdPartyScanContext(
                    paths = paths,
                    yaml = yaml,
                    linterDir = toolsDir,
                    logDir = logDir,
                    noBuild = noBuild,
                    noStatistics = noStatistics,
                    compileCommands = compileCommands,
                    clangArgs = clangArgs,
                    customTools = tools,
                )

            try {
                linter.runAnalysis(context)
                terminal.println("Analysis complete. Results saved to ${paths.resultsDir}")
            } catch (e: Exception) {
                terminal.error("Analysis failed: ${e.message}")
                throw ProgramResult(1)
            }
        }

    private fun loadYaml(projectDir: Path): QodanaYaml? {
        val yamlFile =
            listOf("qodana.yaml", "qodana.yml")
                .map { projectDir.resolve(it) }
                .firstOrNull { Files.exists(it) }
                ?: return null

        val mapper = YAMLMapper().registerModule(kotlinModule())
        return mapper.readValue<QodanaYaml>(yamlFile.toFile())
    }
}

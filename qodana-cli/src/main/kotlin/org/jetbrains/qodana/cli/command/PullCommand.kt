package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.engine.port.ContainerEngine
import org.jetbrains.qodana.engine.scan.ProjectDetector
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Path

class PullCommand(
    private val containerEngine: ContainerEngine,
    private val terminal: Terminal,
) : CliktCommand("pull") {

    override fun help(context: Context) = "Pull the Qodana Docker image"

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val linter by option("-l", "--linter", help = "Linter image to pull")
    private val image by option("--image", help = "Docker image to pull")
    private val configName by option("--config", help = "Custom configuration file instead of qodana.yaml")

    override fun run() = runBlocking {
        val absProjectDir = projectDir.toAbsolutePath().normalize()
        val yaml = CliPathResolver.loadYaml(absProjectDir, configName)
        val yamlWithinDocker = yaml?.withinDocker?.trim()?.lowercase()
        val noCliOverrides = linter.isNullOrBlank() && image.isNullOrBlank()
        val yamlLinter = yaml?.linter?.takeIf { it.isNotBlank() }
        val yamlImage = yaml?.image?.takeIf { it.isNotBlank() }
        val yamlLinterImage = resolveImageFromLinter(yamlLinter)
        val yamlLinterByName = yamlLinter?.let { Linters.findByName(it) }

        if (noCliOverrides && yamlLinter != null && yamlImage == null && yamlLinterImage == null) {
            terminal.error("Unknown linter '$yamlLinter' in qodana.yaml. Use --image for custom docker image.")
            throw ProgramResult(1)
        }

        val yamlNativeMode = when {
            yaml?.ide != null && yamlImage == null && yamlLinter == null -> true
            yamlWithinDocker == "false" && yamlImage == null && yamlLinterByName != null -> true
            else -> false
        }

        if (noCliOverrides && yamlNativeMode) {
            terminal.println("Native mode is used, skipping pull")
            return@runBlocking
        }

        val linterImageFromCli = resolveImageFromLinter(linter)
        if (linter != null && linterImageFromCli == null) {
            terminal.error("Unknown linter '$linter'. Use --image for custom docker image.")
            throw ProgramResult(1)
        }

        val resolvedImage = image
            ?: linterImageFromCli
            ?: yamlImage
            ?: yamlLinterImage
            ?: ProjectDetector.detectLinter(absProjectDir)?.image()
            ?: run {
                terminal.error("No linter specified. Use --linter or configure in qodana.yaml")
                throw ProgramResult(1)
            }

        containerEngine.pull(resolvedImage) { terminal.println(it) }
    }

    private fun resolveImageFromLinter(linterValue: String?): String? {
        if (linterValue.isNullOrBlank()) return null
        Linters.findByName(linterValue)?.let { return it.image() }
        Linters.findByDockerImage(linterValue)?.let { return linterValue }
        return null
    }
}

package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.engine.config.EffectiveConfig
import org.jetbrains.qodana.engine.port.ContainerEngine
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

    override fun run() = runBlocking {
        val yaml = EffectiveConfig.load(projectDir)

        // Native mode: if ide is set and no docker image specified, skip pull
        if (yaml?.ide != null && linter == null && image == null) {
            terminal.println("Native mode detected (ide: ${yaml.ide}), nothing to pull")
            return@runBlocking
        }

        val resolvedImage = image ?: linter
            ?: yaml?.let { it.image ?: it.linter }
            ?: run {
                terminal.error("No linter specified. Use --linter or configure in qodana.yaml")
                throw ProgramResult(1)
            }

        containerEngine.pull(resolvedImage) { terminal.println(it) }
    }
}

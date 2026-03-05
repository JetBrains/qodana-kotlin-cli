package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Files
import java.nio.file.Path

class InitCommand(
    private val terminal: Terminal,
) : CliktCommand("init") {

    override fun help(context: Context) = "Configure a Qodana project by creating a qodana.yaml file"

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))

    override fun run() {
        val yamlFile = projectDir.resolve("qodana.yaml")
        if (Files.exists(yamlFile)) {
            terminal.println("qodana.yaml already exists at $yamlFile")
            return
        }

        val defaultYaml = """
            |version: "1.0"
            |linter: jetbrains/qodana-jvm:latest
            |profile:
            |  name: qodana.recommended
        """.trimMargin()

        Files.writeString(yamlFile, defaultYaml)
        terminal.println("Created qodana.yaml at $yamlFile")
    }
}

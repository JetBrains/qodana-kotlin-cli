package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.scan.ProjectDetector
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
        val detectedLinter = ProjectDetector.detectLinter(projectDir)
        val linterName = detectedLinter?.name ?: "jetbrains/qodana-jvm:latest"

        val yamlFile = findExistingYaml(projectDir)
        if (yamlFile != null) {
            updateExistingYaml(yamlFile, linterName)
        } else {
            createNewYaml(projectDir.resolve("qodana.yaml"), linterName)
        }
    }

    private fun findExistingYaml(dir: Path): Path? {
        val yamlNames = listOf("qodana.yaml", "qodana.yml")
        return yamlNames.map { dir.resolve(it) }.firstOrNull { Files.exists(it) }
    }

    private fun updateExistingYaml(yamlFile: Path, linterName: String) {
        val content = Files.readString(yamlFile)
        val lines = content.lines().toMutableList()

        val linterIdx = lines.indexOfFirst { it.trimStart().startsWith("linter:") }
        if (linterIdx >= 0) {
            lines[linterIdx] = "linter: $linterName"
        } else {
            lines.add("linter: $linterName")
        }

        Files.writeString(yamlFile, lines.joinToString("\n"))
        terminal.println("Updated $yamlFile with linter: $linterName")
    }

    private fun createNewYaml(yamlFile: Path, linterName: String) {
        val yaml = """
            |version: "1.0"
            |linter: $linterName
            |profile:
            |  name: qodana.recommended
        """.trimMargin()

        Files.writeString(yamlFile, yaml)
        terminal.println("Created $yamlFile with linter: $linterName")
    }
}

package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner

class ClocCommand(
    private val terminal: Terminal,
    private val processRunner: ProcessRunner = SystemProcessRunner(),
) : CliktCommand("cloc") {

    override fun help(context: Context) =
        "Calculate lines of code for projects"

    private val projectDirs by option("-i", "--project-dir", help = "Project directory")
        .multiple()
    private val output by option("-o", "--output", help = "Output format")
        .default("tabular")

    override fun run() = runBlocking {
        val dirs = projectDirs.ifEmpty { listOf(".") }
        val args = buildList {
            add("--format")
            add(output)
            add("--cocomo")
            addAll(dirs)
        }

        val result = processRunner.run(
            ProcessSpec(
                command = "scc",
                args = args,
            )
        )

        if (!result.isSuccess) {
            if (result.stderr.isNotBlank()) {
                terminal.error(result.stderr)
            }
            if (result.stdout.isNotBlank()) {
                terminal.println(result.stdout)
            }
            throw ProgramResult(1)
        }

        if (result.stdout.isNotBlank()) {
            terminal.println(result.stdout)
        }
    }
}

package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.app.contributors.ContributorAnalyzer
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Files
import java.nio.file.Path

class ContributorsCommand(
    private val contributorAnalyzer: ContributorAnalyzer,
    private val terminal: Terminal,
) : CliktCommand("contributors") {

    override fun help(context: Context) = "Count active contributors in the project"

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val days by option("-d", "--days", help = "Number of days to look back").int().default(90)
    private val noBots by option("--no-bots", help = "Exclude bot accounts").flag(default = true)
    private val output by option("-o", "--output", help = "Output file path").path()

    override fun run() = runBlocking {
        val report = contributorAnalyzer.analyze(
            repoDirs = listOf(projectDir),
            days = days,
            excludeBots = noBots,
        )

        val mapper = ObjectMapper().registerModule(kotlinModule())
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)

        if (output != null) {
            Files.writeString(output!!, json)
            terminal.println("Contributors report written to $output")
        } else {
            terminal.println(json)
        }
    }
}

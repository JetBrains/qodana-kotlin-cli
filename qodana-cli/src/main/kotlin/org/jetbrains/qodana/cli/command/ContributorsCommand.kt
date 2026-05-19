package org.jetbrains.qodana.cli.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import java.nio.file.Path

class ContributorsCommand(
    private val contributorAnalyzer: ContributorAnalyzer,
    private val terminal: Terminal,
) : CliktCommand("contributors") {
    override fun help(context: Context) = "Count active contributors in the project"

    private val projectDirs by option("-i", "--project-dir", help = "Project directory, can be specified multiple times")
        .path(mustExist = true)
        .multiple()
    private val days by option("-d", "--days", help = "Number of days to look back").int().default(90)
    private val output by option("-o", "--output", help = "Output format: tabular or json").default("tabular")

    override fun run() =
        runBlocking {
            val repos = projectDirs.ifEmpty { listOf(Path.of(".")) }
            val report =
                contributorAnalyzer.analyze(
                    repoDirs = repos,
                    days = days,
                    excludeBots = false,
                )

            when (output.lowercase()) {
                "tabular" -> printTabular(report, days, repos.size)
                "json" -> {
                    val mapper = ObjectMapper().registerModule(kotlinModule())
                    val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
                    terminal.println(json)
                }
                else -> throw UsageError("Unknown output format: $output")
            }
        }

    private fun printTabular(
        report: org.jetbrains.qodana.engine.contributors.ContributorsReport,
        days: Int,
        projectsCount: Int,
    ) {
        terminal.println("Active contributors in last $days day(s) across $projectsCount project(s):")
        terminal.println("─".repeat(90))
        terminal.println(String.format("%-30s %-20s %8s %s", "Email", "Username", "Commits", "Projects"))
        terminal.println("─".repeat(90))
        for (contributor in report.contributors) {
            terminal.println(
                String.format(
                    "%-30s %-20s %8d %s",
                    contributor.author.email,
                    contributor.author.username,
                    contributor.count,
                    contributor.projects.joinToString(","),
                ),
            )
        }
        terminal.println("─".repeat(90))
        terminal.println("Total contributors: ${report.total}")
    }
}

package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.jetbrains.qodana.sarif.model.Result
import com.jetbrains.qodana.sarif.model.SarifReport
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.core.port.Terminal

class ViewCommand(
    private val sarifService: () -> SarifService,
    private val terminal: Terminal,
) : CliktCommand("view") {

    constructor(sarifService: SarifService, terminal: Terminal) : this({ sarifService }, terminal)

    override fun help(context: Context) = "View SARIF files in CLI"

    private val sarifFile by option("-f", "--sarif-file", help = "Path to the SARIF file")
        .default("qodana.sarif.json")

    override fun run() {
        val report = sarifService().read(java.nio.file.Path.of(sarifFile)) as? SarifReport ?: return
        val runs = report.runs ?: emptyList()

        terminal.println("")
        var newProblems = 0
        for (run in runs) {
            val results = run.results ?: continue
            for (result in results) {
                val baselineState = result.baselineState
                if (baselineState == null || baselineState == Result.BaselineState.NEW) {
                    newProblems++
                }
                if (result.locations.isNullOrEmpty() || baselineState == Result.BaselineState.UNCHANGED) {
                    continue
                }
                val ruleId = result.ruleId ?: "unknown"
                val message = result.message?.text ?: ""
                val physicalLocation = result.locations?.firstOrNull()?.physicalLocation
                val uri = physicalLocation?.artifactLocation?.uri ?: ""
                val line = physicalLocation?.region?.startLine

                val loc = if (uri.isNotEmpty() && line != null) "$uri:$line" else uri
                terminal.println("  $ruleId: $message ($loc)")
            }
        }
        terminal.println("")
        terminal.println(problemsFoundMessage(newProblems))
    }

    private fun problemsFoundMessage(newProblems: Int): String {
        return when (newProblems) {
            0 -> "It seems all right 👌 No new problems found according to the checks applied"
            1 -> "Found 1 new problem according to the checks applied"
            else -> "Found $newProblems new problems according to the checks applied"
        }
    }
}

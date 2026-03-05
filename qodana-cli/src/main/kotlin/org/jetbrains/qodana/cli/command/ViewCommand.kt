package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.core.port.Terminal

class ViewCommand(
    private val sarifService: SarifService,
    private val terminal: Terminal,
) : CliktCommand("view") {

    override fun help(context: Context) = "View SARIF files in CLI"

    private val sarifFile by option("-f", "--sarif-file", help = "Path to the SARIF file")
        .default("qodana.sarif.json")

    @Suppress("UNCHECKED_CAST")
    override fun run() {
        val report = sarifService.read(java.nio.file.Path.of(sarifFile)) as? Map<String, Any> ?: return
        val runs = report["runs"] as? List<Map<String, Any>> ?: return

        terminal.println("")
        var problemCount = 0
        for (run in runs) {
            val results = run["results"] as? List<Map<String, Any>> ?: continue
            for (result in results) {
                val ruleId = result["ruleId"] as? String ?: "unknown"
                val message = (result["message"] as? Map<String, Any>)?.get("text") as? String ?: ""
                val locations = result["locations"] as? List<Map<String, Any>>
                val location = locations?.firstOrNull()
                val physicalLocation = (location?.get("physicalLocation") as? Map<String, Any>)
                val artifactLocation = (physicalLocation?.get("artifactLocation") as? Map<String, Any>)
                val uri = artifactLocation?.get("uri") as? String ?: ""
                val region = physicalLocation?.get("region") as? Map<String, Any>
                val line = (region?.get("startLine") as? Number)?.toInt()

                val loc = if (uri.isNotEmpty() && line != null) "$uri:$line" else uri
                terminal.println("  $ruleId: $message ($loc)")
                problemCount++
            }
        }
        terminal.println("")
        terminal.println("Found $problemCount problems")
    }
}

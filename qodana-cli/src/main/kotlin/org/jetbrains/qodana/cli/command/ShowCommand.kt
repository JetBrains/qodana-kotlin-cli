package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Files
import java.nio.file.Path

class ShowCommand(
    private val terminal: Terminal,
) : CliktCommand("show") {

    override fun help(context: Context) = "Show Qodana report in the browser"

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val reportDir by option("-r", "--report-dir", help = "Report directory").path()

    override fun run() {
        val dir = reportDir ?: projectDir.resolve("results").resolve("report")
        val indexHtml = dir.resolve("index.html")
        if (!Files.exists(indexHtml)) {
            terminal.error("No report found at $indexHtml. Run 'qodana scan' first.")
            throw ProgramResult(1)
        }

        terminal.println("Opening report at $indexHtml")
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("mac") -> arrayOf("open", indexHtml.toString())
                os.contains("linux") -> arrayOf("xdg-open", indexHtml.toString())
                os.contains("windows") -> arrayOf("cmd", "/c", "start", indexHtml.toString())
                else -> {
                    terminal.println("Report path: $indexHtml")
                    return
                }
            }
            Runtime.getRuntime().exec(cmd)
        } catch (_: Exception) {
            terminal.println("Could not open browser. Report path: $indexHtml")
        }
    }
}

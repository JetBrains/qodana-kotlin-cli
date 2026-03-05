package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.model.AuthContext
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Path

class SendCommand(
    private val reportPublisher: ReportPublishUseCase,
    private val terminal: Terminal,
) : CliktCommand("send") {

    override fun help(context: Context) = "Send Qodana report to Qodana Cloud"

    private val resultsDir by option("-o", "--results-dir", help = "Directory with analysis results")
        .path(mustExist = true)
        .default(Path.of("./results"))
    private val analysisId by option("--analysis-id", help = "Analysis ID")

    override fun run() = runBlocking {
        val token = System.getenv("QODANA_TOKEN")
        if (token.isNullOrBlank()) {
            terminal.error("QODANA_TOKEN environment variable is not set")
            throw ProgramResult(1)
        }

        val auth = AuthContext(
            token = token,
            endpoint = System.getenv("QODANA_ENDPOINT") ?: "https://qodana.cloud",
        )

        val result = reportPublisher.publish(
            analysisId = analysisId ?: "",
            reportPath = resultsDir,
            auth = auth,
        )

        result.onSuccess {
            if (it.success) terminal.println("Report published: ${it.url}")
            else {
                terminal.error("Failed to publish report")
                throw ProgramResult(1)
            }
        }.onFailure {
            terminal.error("Failed to publish report: ${it.message}")
            throw ProgramResult(1)
        }
        Unit
    }
}

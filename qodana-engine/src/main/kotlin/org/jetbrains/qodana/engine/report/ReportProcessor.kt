package org.jetbrains.qodana.engine.report

import com.jetbrains.qodana.sarif.model.SarifReport
import org.jetbrains.qodana.core.model.BaselineResult
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.engine.model.ReportOptions
import org.jetbrains.qodana.engine.port.ReportConverter
import java.nio.file.Path

data class ProcessedReport(
    val totalProblems: Int,
    val newProblems: Int,
    val exitCode: Int,
    val baselineResult: BaselineResult?,
)

class ReportProcessor(
    private val sarifService: SarifService,
    private val reportConverter: ReportConverter,
) {
    companion object {
        private const val SARIF_FILENAME = "qodana.sarif.json"
    }

    fun process(
        resultsDir: Path,
        reportDir: Path,
        reportOptions: ReportOptions,
        failThreshold: Int? = null,
        analysisExitCode: Int = 0,
    ): ProcessedReport {
        val sarifPath = resultsDir.resolve(SARIF_FILENAME)

        val baselineResult =
            reportOptions.baselinePath?.let { baselinePath ->
                sarifService.baselineCompare(
                    report = sarifPath,
                    baseline = baselinePath,
                    includeAbsent = reportOptions.baselineIncludeAbsent,
                )
            }

        val totalProblems = countProblems(sarifPath)
        val newProblems = baselineResult?.newCount ?: totalProblems

        val exitCode =
            determineExitCode(
                newProblems = newProblems,
                failThreshold = failThreshold,
                analysisExitCode = analysisExitCode,
            )

        if (reportOptions.saveReport) {
            reportConverter.convertToHtml(resultsDir, reportDir)
        }

        return ProcessedReport(
            totalProblems = totalProblems,
            newProblems = newProblems,
            exitCode = exitCode,
            baselineResult = baselineResult,
        )
    }

    private fun countProblems(sarifPath: Path): Int {
        val report = runCatching { sarifService.read(sarifPath) }.getOrNull()
        return extractProblemCount(report)
    }

    private fun determineExitCode(
        newProblems: Int,
        failThreshold: Int?,
        analysisExitCode: Int = 0,
    ): Int {
        if (analysisExitCode != 0) {
            return analysisExitCode
        }

        if (failThreshold != null && newProblems >= failThreshold) {
            return ExitCode.FAIL_THRESHOLD.code
        }

        return ExitCode.SUCCESS.code
    }

    private fun extractProblemCount(report: Any?): Int {
        val sarifReport = report as? SarifReport ?: return 0
        return sarifReport.runs
            ?.sumOf { run -> run.results?.size ?: 0 }
            ?: 0
    }

    fun readReport(resultsDir: Path): SarifReport? {
        val report = sarifService.read(resultsDir.resolve(SARIF_FILENAME))
        return report as? SarifReport
    }

    fun formatProblems(resultsDir: Path): List<String> {
        val report = readReport(resultsDir) ?: return emptyList()
        val lines = mutableListOf<String>()
        for (run in report.runs ?: emptyList()) {
            for (result in run.results ?: emptyList()) {
                val ruleId = result.ruleId ?: "unknown"
                val message = result.message?.text ?: ""
                val location = result.locations?.firstOrNull()?.physicalLocation
                val file = location?.artifactLocation?.uri ?: ""
                val line = location?.region?.startLine
                val formattedLocation = if (!file.isBlank() && line != null) "$file:$line" else file
                lines.add("$ruleId: $message${if (formattedLocation.isNotBlank()) " ($formattedLocation)" else ""}")
            }
        }
        return lines
    }
}

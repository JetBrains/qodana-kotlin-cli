package org.jetbrains.qodana.app.report

import org.jetbrains.qodana.core.model.BaselineResult
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.model.ReportOptions
import org.jetbrains.qodana.core.port.ReportConverter
import org.jetbrains.qodana.core.port.SarifService
import java.nio.file.Path

data class ProcessedReport(
    val totalProblems: Int,
    val newProblems: Int,
    val exitCode: ExitCode,
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
    ): ProcessedReport {
        val sarifPath = resultsDir.resolve(SARIF_FILENAME)

        val baselineResult = reportOptions.baselinePath?.let { baselinePath ->
            sarifService.baselineCompare(
                report = sarifPath,
                baseline = baselinePath,
                includeAbsent = reportOptions.baselineIncludeAbsent,
            )
        }

        val totalProblems = countProblems(sarifPath)
        val newProblems = baselineResult?.newCount ?: totalProblems

        val exitCode = determineExitCode(
            newProblems = newProblems,
            failThreshold = failThreshold,
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
        val report = sarifService.read(sarifPath)
        return extractProblemCount(report)
    }

    private fun determineExitCode(newProblems: Int, failThreshold: Int?): ExitCode {
        if (newProblems == 0) return ExitCode.SUCCESS

        if (failThreshold != null && newProblems >= failThreshold) {
            return ExitCode.FAIL_THRESHOLD
        }

        return if (newProblems > 0) ExitCode.THRESHOLD_REACHED else ExitCode.SUCCESS
    }

    /**
     * Extracts problem count from a SARIF report object.
     * The report is an opaque Any from [SarifService.read]; we use reflection-free
     * duck-typing: runs[].results[].size summed.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractProblemCount(report: Any): Int {
        return try {
            val runs = (report as? Map<String, Any>)?.get("runs") as? List<Map<String, Any>>
                ?: return 0
            runs.sumOf { run ->
                val results = run["results"] as? List<*> ?: emptyList<Any>()
                results.size
            }
        } catch (_: Exception) {
            0
        }
    }
}

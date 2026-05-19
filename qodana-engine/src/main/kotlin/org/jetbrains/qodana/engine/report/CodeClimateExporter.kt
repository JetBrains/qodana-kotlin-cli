package org.jetbrains.qodana.engine.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.jetbrains.qodana.sarif.model.Result
import com.jetbrains.qodana.sarif.model.SarifReport
import org.jetbrains.qodana.core.port.SarifService
import java.nio.file.Path

class CodeClimateExporter(
    private val sarifService: SarifService,
) {
    companion object {
        private const val SARIF_FILENAME = "qodana.sarif.json"
        private const val CODE_CLIMATE_FILENAME = "gl-code-quality-report.json"

        private val CODE_CLIMATE_SEVERITY =
            mapOf(
                "error" to "critical",
                "warning" to "major",
                "note" to "minor",
                "critical" to "blocker",
                "high" to "critical",
                "moderate" to "major",
                "low" to "minor",
                "info" to "info",
            )
    }

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(kotlinModule())

    fun export(resultsDir: Path) {
        val sarifPath = resultsDir.resolve(SARIF_FILENAME)
        val report = sarifService.read(sarifPath) as? SarifReport ?: return
        val issues = convertToCodeClimate(report)
        val outputPath = resultsDir.resolve(CODE_CLIMATE_FILENAME)
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), issues)
    }

    private fun convertToCodeClimate(report: SarifReport): List<Map<String, Any>> =
        (report.runs ?: emptyList()).flatMap { run ->
            (run.results ?: emptyList())
                .filter { result ->
                    !result.locations.isNullOrEmpty() && result.baselineState != Result.BaselineState.UNCHANGED
                }.mapNotNull { result -> convertResult(result) }
        }

    private fun convertResult(result: Result): Map<String, Any>? {
        val ruleId = result.ruleId ?: return null
        val message = result.message?.text ?: ""
        val location = result.locations?.firstOrNull()?.physicalLocation
        val path = location?.artifactLocation?.uri ?: ""
        val line = location?.region?.startLine ?: 0
        val severity = mapSeverity(resolveSeverity(result))
        val fingerprint =
            readFingerprint(result)
                ?: throw IllegalStateException("failed to get fingerprint from result: $result")

        return buildMap {
            put("check_name", ruleId)
            put("description", message)
            put("fingerprint", fingerprint)
            put("severity", severity)
            put(
                "location",
                mapOf(
                    "path" to path,
                    "lines" to mapOf("begin" to line),
                ),
            )
        }
    }

    private fun resolveSeverity(result: Result): String {
        val qodanaSeverity = result.properties?.get("qodanaSeverity") as? String
        if (!qodanaSeverity.isNullOrBlank()) {
            return qodanaSeverity.lowercase()
        }
        return result.level?.value() ?: "note"
    }

    private fun mapSeverity(severity: String): String = CODE_CLIMATE_SEVERITY[severity] ?: ""

    private fun readFingerprint(result: Result): String? {
        val partialFingerprints = result.partialFingerprints ?: return null
        return partialFingerprints.getLastValue("equalIndicator")
    }
}

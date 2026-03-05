package org.jetbrains.qodana.engine.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.jetbrains.qodana.core.port.SarifService
import java.nio.file.Path

class CodeClimateExporter(
    private val sarifService: SarifService,
) {
    companion object {
        private const val SARIF_FILENAME = "qodana.sarif.json"
        private const val CODE_CLIMATE_FILENAME = "code-climate.json"
    }

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())

    fun export(resultsDir: Path) {
        val sarifPath = resultsDir.resolve(SARIF_FILENAME)
        val report = sarifService.read(sarifPath)
        val issues = convertToCodeClimate(report)
        val outputPath = resultsDir.resolve(CODE_CLIMATE_FILENAME)
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), issues)
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertToCodeClimate(report: Any): List<Map<String, Any>> {
        val runs = (report as? Map<String, Any>)?.get("runs") as? List<Map<String, Any>>
            ?: return emptyList()

        return runs.flatMap { run ->
            val results = run["results"] as? List<Map<String, Any>> ?: emptyList()
            results.mapNotNull { result -> convertResult(result) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertResult(result: Map<String, Any>): Map<String, Any>? {
        val ruleId = result["ruleId"] as? String ?: return null
        val message = (result["message"] as? Map<String, Any>)?.get("text") as? String ?: ""
        val locations = result["locations"] as? List<Map<String, Any>> ?: emptyList()
        val location = locations.firstOrNull()
        val physicalLocation = (location?.get("physicalLocation") as? Map<String, Any>)
        val artifactLocation = physicalLocation?.get("artifactLocation") as? Map<String, Any>
        val region = physicalLocation?.get("region") as? Map<String, Any>

        val path = artifactLocation?.get("uri") as? String ?: ""
        val line = (region?.get("startLine") as? Number)?.toInt() ?: 1

        val severity = mapSeverity(result["level"] as? String)

        return buildMap {
            put("type", "issue")
            put("check_name", ruleId)
            put("description", message)
            put("severity", severity)
            put("categories", listOf("Bug Risk"))
            put("fingerprint", buildFingerprint(ruleId, path, line))
            put("location", mapOf(
                "path" to path,
                "lines" to mapOf("begin" to line),
            ))
        }
    }

    private fun mapSeverity(level: String?): String = when (level) {
        "error" -> "critical"
        "warning" -> "major"
        "note" -> "minor"
        else -> "info"
    }

    private fun buildFingerprint(ruleId: String, path: String, line: Int): String {
        val raw = "$ruleId:$path:$line"
        return raw.hashCode().toUInt().toString(16).padStart(8, '0')
    }
}

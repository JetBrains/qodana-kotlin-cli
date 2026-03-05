package org.jetbrains.qodana.engine.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.core.port.SarifService
import java.nio.file.Path

class BitBucketExporter(
    private val sarifService: SarifService,
    private val http: HttpTransport,
) {
    companion object {
        private const val SARIF_FILENAME = "qodana.sarif.json"
        private const val MAX_ANNOTATIONS_PER_REQUEST = 100
    }

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())

    suspend fun export(
        resultsDir: Path,
        bitbucketUrl: String,
        workspace: String,
        repoSlug: String,
        commitHash: String,
        token: String,
        reportId: String = "qodana",
        reportTitle: String = "Qodana Analysis",
    ) {
        val sarifPath = resultsDir.resolve(SARIF_FILENAME)
        val report = sarifService.read(sarifPath)
        val annotations = convertToAnnotations(report)

        val baseUrl = "${bitbucketUrl.trimEnd('/')}/2.0/repositories/$workspace/$repoSlug/commit/$commitHash/reports"

        createReport(baseUrl, token, reportId, reportTitle, annotations.size)
        postAnnotations(baseUrl, token, reportId, annotations)
    }

    private suspend fun createReport(
        baseUrl: String,
        token: String,
        reportId: String,
        reportTitle: String,
        totalAnnotations: Int,
    ) {
        val body = mapper.writeValueAsBytes(mapOf(
            "title" to reportTitle,
            "details" to "Qodana found $totalAnnotations issue(s)",
            "report_type" to "BUG",
            "reporter" to "Qodana",
            "result" to if (totalAnnotations == 0) "PASSED" else "FAILED",
        ))

        http.post(
            url = "$baseUrl/$reportId",
            body = body,
            contentType = "application/json",
            headers = authHeaders(token),
        )
    }

    private suspend fun postAnnotations(
        baseUrl: String,
        token: String,
        reportId: String,
        annotations: List<Map<String, Any>>,
    ) {
        val annotationsUrl = "$baseUrl/$reportId/annotations"

        annotations.chunked(MAX_ANNOTATIONS_PER_REQUEST).forEach { batch ->
            val body = mapper.writeValueAsBytes(batch)
            http.post(
                url = annotationsUrl,
                body = body,
                contentType = "application/json",
                headers = authHeaders(token),
            )
        }
    }

    private fun authHeaders(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
    )

    @Suppress("UNCHECKED_CAST")
    private fun convertToAnnotations(report: Any): List<Map<String, Any>> {
        val runs = (report as? Map<String, Any>)?.get("runs") as? List<Map<String, Any>>
            ?: return emptyList()

        return runs.flatMap { run ->
            val results = run["results"] as? List<Map<String, Any>> ?: emptyList()
            results.mapIndexedNotNull { index, result -> convertResult(result, index) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertResult(result: Map<String, Any>, index: Int): Map<String, Any>? {
        val ruleId = result["ruleId"] as? String ?: return null
        val message = (result["message"] as? Map<String, Any>)?.get("text") as? String ?: ""
        val locations = result["locations"] as? List<Map<String, Any>> ?: emptyList()
        val location = locations.firstOrNull()
        val physicalLocation = location?.get("physicalLocation") as? Map<String, Any>
        val artifactLocation = physicalLocation?.get("artifactLocation") as? Map<String, Any>
        val region = physicalLocation?.get("region") as? Map<String, Any>

        val path = artifactLocation?.get("uri") as? String ?: ""
        val line = (region?.get("startLine") as? Number)?.toInt() ?: 1

        val severity = mapSeverity(result["level"] as? String)

        return buildMap {
            put("external_id", "qodana-$index")
            put("title", ruleId)
            put("annotation_type", "BUG")
            put("summary", message)
            put("severity", severity)
            put("path", path)
            put("line", line)
        }
    }

    private fun mapSeverity(level: String?): String = when (level) {
        "error" -> "CRITICAL"
        "warning" -> "HIGH"
        "note" -> "MEDIUM"
        else -> "LOW"
    }
}

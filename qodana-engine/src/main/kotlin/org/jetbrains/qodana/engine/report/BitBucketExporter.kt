package org.jetbrains.qodana.engine.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.jetbrains.qodana.sarif.model.Result
import com.jetbrains.qodana.sarif.model.SarifReport
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.engine.cloud.getReportUrl
import org.jetbrains.qodana.engine.port.HttpTransport
import java.net.URI
import java.nio.file.Path

class BitBucketExporter(
    private val sarifService: SarifService,
    private val http: HttpTransport,
    private val getEnv: (String) -> String? = System::getenv,
) {
    companion object {
        private const val SARIF_FILENAME = "qodana.sarif.json"
        private const val MAX_ANNOTATIONS_PER_REQUEST = 100
        private const val MAX_ANNOTATIONS_TOTAL = 1000
        private const val DEFAULT_CLOUD_API = "https://api.bitbucket.org/2.0"
        private const val PIPELINES_CLOUD_API = "http://api.bitbucket.org/2.0"
        private const val DEFAULT_REPORT_TITLE = "Qodana Analysis"
        private const val BITBUCKET_REPORTER = "JetBrains Qodana"
        private const val BITBUCKET_LOGO_URL = "https://avatars.githubusercontent.com/u/139879315"
        private const val ANNOTATION_TYPE = "CODE_SMELL"
        private const val BITBUCKET_PIPELINE_UUID = "BITBUCKET_PIPELINE_UUID"

        private val BITBUCKET_SEVERITY =
            mapOf(
                "error" to "HIGH",
                "warning" to "MEDIUM",
                "note" to "LOW",
                "critical" to "HIGH",
                "high" to "HIGH",
                "moderate" to "MEDIUM",
                "low" to "LOW",
                "info" to "INFO",
            )
    }

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(kotlinModule())

    suspend fun export(
        resultsDir: Path,
        bitbucketUrl: String,
        workspace: String,
        repoSlug: String,
        commitHash: String,
        token: String,
        reportId: String = "qodana",
        reportTitle: String = DEFAULT_REPORT_TITLE,
    ) {
        val sarifPath = resultsDir.resolve(SARIF_FILENAME)
        val report = sarifService.read(sarifPath) as? SarifReport ?: return
        val reportLink = getReportUrl(resultsDir.toString()).ifBlank { "" }
        val annotations = convertToAnnotations(report, reportLink).take(MAX_ANNOTATIONS_TOTAL)
        val effectiveTitle =
            if (reportTitle == DEFAULT_REPORT_TITLE) {
                resolveToolName(report)
            } else {
                reportTitle
            }

        val baseUrl = "${resolveApiBaseUrl(bitbucketUrl)}/repositories/$workspace/$repoSlug/commit/$commitHash/reports"

        createReport(
            baseUrl = baseUrl,
            token = token,
            reportId = reportId,
            reportTitle = effectiveTitle,
            totalAnnotations = annotations.size,
            reportLink = reportLink,
        )
        postAnnotations(baseUrl, token, reportId, annotations)
    }

    private suspend fun createReport(
        baseUrl: String,
        token: String,
        reportId: String,
        reportTitle: String,
        totalAnnotations: Int,
        reportLink: String,
    ) {
        val body =
            mapper.writeValueAsBytes(
                mapOf(
                    "title" to reportTitle,
                    "details" to problemsFoundMessage(totalAnnotations),
                    "report_type" to "BUG",
                    "reporter" to BITBUCKET_REPORTER,
                    "logo_url" to BITBUCKET_LOGO_URL,
                    "link" to reportLink,
                    "result" to if (totalAnnotations == 0) "PASSED" else "FAILED",
                ),
            )

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

    private fun authHeaders(token: String): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $token",
        )

    private fun convertToAnnotations(
        report: SarifReport,
        reportLink: String,
    ): List<Map<String, Any>> {
        val annotations = mutableListOf<Map<String, Any>>()

        for (run in report.runs ?: emptyList()) {
            val ruleDescriptions =
                (run.tool?.extensions ?: emptySet())
                    .flatMap { component -> component.rules ?: emptyList() }
                    .associate { rule -> rule.id to (rule.shortDescription?.text ?: "") }

            for (result in run.results ?: emptyList()) {
                if (result.locations.isNullOrEmpty() || result.baselineState == Result.BaselineState.UNCHANGED) {
                    continue
                }
                val annotation = convertResult(result, ruleDescriptions[result.ruleId] ?: "", reportLink)
                if (annotation != null) {
                    annotations += annotation
                }
            }
        }

        return annotations
    }

    private fun convertResult(
        result: Result,
        ruleDescription: String,
        reportLink: String,
    ): Map<String, Any>? {
        val ruleId = result.ruleId ?: return null
        val message = result.message?.text ?: ""
        val location = result.locations?.firstOrNull()?.physicalLocation
        val fingerprint =
            readFingerprint(result)
                ?: throw IllegalStateException("failed to get fingerprint from result: $result")
        val path = location?.artifactLocation?.uri ?: ""
        val line = location?.region?.startLine ?: 0
        val severity = mapSeverity(resolveSeverity(result))

        return buildMap {
            put("external_id", fingerprint)
            put("annotation_type", ANNOTATION_TYPE)
            put("summary", "$ruleId: $message")
            put("details", ruleDescription)
            put("severity", severity)
            put("path", path)
            put("line", line)
            put("link", reportLink)
        }
    }

    private fun resolveSeverity(result: Result): String {
        val qodanaSeverity = result.properties?.get("qodanaSeverity") as? String
        if (!qodanaSeverity.isNullOrBlank()) {
            return qodanaSeverity.lowercase()
        }
        return result.level?.value() ?: "note"
    }

    private fun mapSeverity(severity: String): String = BITBUCKET_SEVERITY[severity] ?: "LOW"

    private fun readFingerprint(result: Result): String? {
        val partialFingerprints = result.partialFingerprints ?: return null
        return partialFingerprints.getLastValue("equalIndicator")
    }

    private fun resolveToolName(report: SarifReport): String {
        val firstRun = report.runs?.firstOrNull()
        val toolName = firstRun?.tool?.driver?.fullName ?: ""
        return "$toolName "
    }

    private fun problemsFoundMessage(count: Int): String =
        when (count) {
            0 -> "It seems all right 👌 No new problems found according to the checks applied"
            1 -> "Found 1 new problem according to the checks applied"
            else -> "Found $count new problems according to the checks applied"
        }

    private fun resolveApiBaseUrl(rawUrl: String): String {
        if (isBitBucketPipeline()) {
            // In BitBucket Pipelines, the API is reached via local proxy with api.bitbucket.org URL.
            return PIPELINES_CLOUD_API
        }

        val value = rawUrl.trim()
        if (value.isBlank()) return DEFAULT_CLOUD_API

        val normalized = if ("://" in value) value else "https://$value"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return DEFAULT_CLOUD_API

        val scheme = uri.scheme ?: "https"
        val host = uri.host?.lowercase() ?: return DEFAULT_CLOUD_API
        val hostAndPort = if (uri.port > 0) "$host:${uri.port}" else host
        val path = (uri.path ?: "").trimEnd('/')

        return when {
            host == "bitbucket.org" -> DEFAULT_CLOUD_API
            host == "api.bitbucket.org" -> {
                if (path.endsWith("/2.0")) {
                    "$scheme://$hostAndPort$path"
                } else {
                    "$scheme://$hostAndPort/2.0"
                }
            }
            path.contains("/rest/api/") -> "$scheme://$hostAndPort$path"
            else -> "$scheme://$hostAndPort/rest/api/1.0"
        }
    }

    private fun isBitBucketPipeline(): Boolean = !getEnv(BITBUCKET_PIPELINE_UUID).isNullOrBlank()
}

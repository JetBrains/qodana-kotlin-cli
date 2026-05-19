package org.jetbrains.qodana.engine.report

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

object SarifUtils {
    const val SARIF_EXTENSION = ".sarif.json"

    fun getSarifPath(resultsDir: String): String = "$resultsDir/qodana$SARIF_EXTENSION"

    fun getShortSarifPath(resultsDir: String): String = "$resultsDir/qodana-short$SARIF_EXTENSION"

    fun getSeverity(result: Map<String, Any?>): String {
        val properties = result["properties"] as? Map<*, *>
        if (properties != null) {
            val severity = properties["qodanaSeverity"] as? String
            if (severity != null) return severity
        }
        return (result["level"] as? String) ?: "note"
    }

    fun getFingerprint(result: Map<String, Any?>): String {
        val partialFingerprints = result["partialFingerprints"] as? Map<*, *> ?: return ""
        val v2 = partialFingerprints["equalIndicator/v2"] as? String
        if (v2 != null) return v2
        val v1 = partialFingerprints["equalIndicator/v1"] as? String
        if (v1 != null) return v1
        return ""
    }

    fun removeDuplicates(results: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (results.isEmpty()) return results
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            val fingerprint = getFingerprint(result)
            if (fingerprint.isEmpty()) {
                true
            } else {
                seen.add(fingerprint)
            }
        }
    }

    fun runGuid(envGuid: String? = System.getenv("QODANA_AUTOMATION_GUID")): String =
        if (!envGuid.isNullOrEmpty()) envGuid else UUID.randomUUID().toString()

    fun reportId(
        productCode: String,
        envReportId: String? = System.getenv("QODANA_REPORT_ID"),
        envProjectId: String? = System.getenv("QODANA_PROJECT_ID"),
    ): String {
        if (!envReportId.isNullOrEmpty()) return envReportId
        val projectId = if (!envProjectId.isNullOrEmpty()) envProjectId else productCode
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "$projectId/qodana/$date"
    }

    fun jobUrl(envJobUrl: String? = System.getenv("QODANA_JOB_URL")): String = envJobUrl ?: ""

    fun findSarifFiles(root: java.nio.file.Path): List<java.nio.file.Path> =
        java.nio.file.Files
            .walk(root)
            .filter {
                !java.nio.file.Files
                    .isDirectory(it)
            }.filter {
                it.fileName
                    .toString()
                    .lowercase()
                    .endsWith(SARIF_EXTENSION)
            }.sorted()
            .toList()

    /**
     * Searches for a rule description (shortDescription.text) across all runs.
     */
    @Suppress("UNCHECKED_CAST")
    fun getRuleDescription(
        report: Map<String, Any?>,
        ruleId: String,
    ): String {
        val runs = report["runs"] as? List<Map<String, Any?>> ?: return ""
        for (run in runs) {
            val tool = run["tool"] as? Map<String, Any?> ?: continue
            val extensions = tool["extensions"] as? List<Map<String, Any?>> ?: continue
            for (ext in extensions) {
                val rules = ext["rules"] as? List<Map<String, Any?>> ?: continue
                for (rule in rules) {
                    if (rule["id"] == ruleId) {
                        val desc = rule["shortDescription"] as? Map<String, Any?> ?: continue
                        return desc["text"] as? String ?: ""
                    }
                }
            }
        }
        return ""
    }

    /**
     * Formats a SARIF problem for display.
     * Returns null if result is null.
     */
    @Suppress("UNCHECKED_CAST")
    fun formatSarifProblem(result: Map<String, Any?>?): String? {
        if (result == null) return null
        val severity = getSeverity(result)
        val message = (result["message"] as? Map<String, Any?>)?.get("text") as? String ?: ""
        val ruleId = result["ruleId"] as? String ?: ""

        val locations = result["locations"] as? List<Map<String, Any?>>
        val location = locations?.firstOrNull()
        val physical = location?.get("physicalLocation") as? Map<String, Any?>

        val file = (physical?.get("artifactLocation") as? Map<String, Any?>)?.get("uri") as? String ?: ""
        val region = physical?.get("region") as? Map<String, Any?>
        val line = (region?.get("startLine") as? Number)?.toInt() ?: 0
        val col = (region?.get("startColumn") as? Number)?.toInt() ?: 0

        return buildString {
            append("[$severity] $ruleId: $message")
            if (file.isNotEmpty()) {
                append(" at $file")
                if (line > 0) {
                    append(":$line")
                    if (col > 0) append(":$col")
                }
            }
        }
    }

    /**
     * Creates a short SARIF by stripping results, artifacts, extensions, and rules.
     */
    @Suppress("UNCHECKED_CAST")
    fun makeShortSarif(report: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val runs = report["runs"] as? List<MutableMap<String, Any?>> ?: return report
        for (run in runs) {
            run.remove("results")
            run.remove("artifacts")
            val tool = run["tool"] as? MutableMap<String, Any?>
            tool?.remove("extensions")
            val driver = tool?.get("driver") as? MutableMap<String, Any?>
            driver?.remove("rules")
            driver?.remove("taxa")
        }
        return report
    }
}

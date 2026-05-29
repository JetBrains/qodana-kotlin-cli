package org.jetbrains.qodana.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import java.nio.file.Path

/**
 * Reduces a SARIF report to a sorted multiset of (ruleId, uri, startLine)
 * tuples for structural comparison. URIs are relativised under `projectRoot`
 * so machine-specific scan locations cancel; volatile fields (timestamps,
 * `tool.driver.semanticVersion`, `run.invocations`, `partialFingerprints`)
 * are dropped; results from all `runs[]` are flattened.
 */
object SarifCompare {
    private val mapper = ObjectMapper()

    fun normalize(
        reportPath: Path,
        projectRoot: Path,
    ): List<String> {
        val root = mapper.readTree(reportPath.toFile())
        val tuples = mutableListOf<String>()
        val runs = root.path("runs")
        if (!runs.isArray) return emptyList()
        for (run in runs) {
            val results = run.path("results") as? ArrayNode ?: continue
            for (result in results) {
                val ruleId = result.path("ruleId").asText("")
                val loc =
                    result
                        .path("locations")
                        .firstOrNull()
                        ?.path("physicalLocation")
                val rawUri =
                    loc
                        ?.path("artifactLocation")
                        ?.path("uri")
                        ?.asText("") ?: ""
                val startLine =
                    loc
                        ?.path("region")
                        ?.path("startLine")
                        ?.asInt(-1) ?: -1
                tuples.add("$ruleId|${normalizeUri(rawUri, projectRoot)}|$startLine")
            }
        }
        return tuples.sorted()
    }

    private fun normalizeUri(
        raw: String,
        projectRoot: Path,
    ): String {
        var s = raw.removePrefix("file://").replace('\\', '/')
        val rootStr =
            projectRoot
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
        if (s.startsWith(rootStr)) {
            s = s.removePrefix(rootStr).removePrefix("/")
        }
        return s
    }

    fun assertEquivalent(
        jvm: Path,
        native: Path,
        projectRoot: Path,
    ) {
        val a = normalize(jvm, projectRoot)
        val b = normalize(native, projectRoot)
        check(a == b) {
            buildString {
                appendLine("SARIF results differ between JVM and native runs.")
                appendLine("JVM total:    ${a.size}; native total: ${b.size}")
                appendLine("JVM-only:    ${a.filterNot(b::contains)}")
                appendLine("native-only: ${b.filterNot(a::contains)}")
            }
        }
    }
}

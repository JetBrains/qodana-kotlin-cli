package org.jetbrains.qodana.images.e2e

import com.jetbrains.qodana.sarif.model.Result
import com.jetbrains.qodana.sarif.model.SarifReport

/** A single failed expectation: its [reason] from the manifest plus a [detail] of what was observed. */
data class Violation(
    val reason: String,
    val detail: String,
)

/**
 * Pure evaluator: given a parsed [SarifReport] and a case's [SarifExpectations],
 * returns the list of [Violation]s (empty iff every expectation holds). No I/O.
 */
class SarifExpectationEvaluator {
    // Qodana writes qodanaSeverity LOWERCASE (qodana-engine BitBucketExporter .lowercase()s it before
    // use; engine test fixtures carry "critical"/"moderate"/"info"). Compare case-insensitively.
    private val severityVocabulary = setOf("critical", "high", "moderate", "low", "info")

    fun evaluate(
        report: SarifReport,
        sarif: SarifExpectations,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        val results = report.runs.orEmpty().flatMap { it.results.orEmpty() }

        sarif.tool?.let { tool -> evaluateTool(report, tool, violations) }
        if (sarif.qodanaSeverityRequired) evaluateSeverity(results, violations)
        for (expectation in sarif.expectations) {
            evaluateExpectation(results, expectation, violations)
        }
        return violations
    }

    private fun evaluateTool(
        report: SarifReport,
        tool: ToolExpectation,
        violations: MutableList<Violation>,
    ) {
        val driver =
            report.runs
                .orEmpty()
                .firstOrNull()
                ?.tool
                ?.driver
        tool.driverName?.let { expected ->
            val actual = driver?.name
            if (actual != expected) {
                violations += Violation("tool.driverName must be \"$expected\"", "tool.driver.name was \"$actual\"")
            }
        }
        tool.driverFullName?.let { expected ->
            val actual = driver?.fullName
            if (actual != expected) {
                violations +=
                    Violation("tool.driverFullName must be \"$expected\"", "tool.driver.fullName was \"$actual\"")
            }
        }
    }

    private fun evaluateSeverity(
        results: List<Result>,
        violations: MutableList<Violation>,
    ) {
        for (result in results) {
            val raw = result.properties?.get("qodanaSeverity") as? String
            if (raw == null || raw.lowercase() !in severityVocabulary) {
                violations +=
                    Violation(
                        "every result must carry properties.qodanaSeverity in $severityVocabulary (case-insensitive)",
                        "result ruleId=${result.ruleId} had qodanaSeverity=$raw",
                    )
            }
        }
    }

    private fun evaluateExpectation(
        results: List<Result>,
        expectation: Expectation,
        violations: MutableList<Violation>,
    ) {
        val matching = results.filter { it.matches(expectation) }
        val n = matching.size

        // count, when present, is the authoritative predicate; otherwise presence
        // present => n>=1, absent => n==0.
        val ok =
            if (expectation.count != null) {
                matchesCount(expectation.count, n)
            } else {
                when (expectation.presence) {
                    "present" -> n >= 1
                    "absent" -> n == 0
                    else -> error("unknown presence \"${expectation.presence}\"")
                }
            }

        if (!ok) {
            val key = expectation.ruleId ?: expectation.ruleIdPattern ?: "<no rule selector>"
            violations +=
                Violation(
                    expectation.reason,
                    "presence=${expectation.presence}" +
                        (expectation.count?.let { " count=$it" } ?: "") +
                        (expectation.uriContains?.let { " uriContains=$it" } ?: "") +
                        (expectation.messageContains?.let { " messageContains=$it" } ?: "") +
                        " for [$key]: matched $n result(s)",
                )
        }
    }

    private fun Result.matches(expectation: Expectation): Boolean =
        matchesRule(expectation) && matchesUri(expectation) && matchesMessage(expectation)

    private fun Result.matchesRule(expectation: Expectation): Boolean {
        // Rule-id selection must NOT pre-reject null-ruleId results: a uri-only scope guard
        // (e.g. "zero findings under third_party/, regardless of rule", QD-9251) must still
        // count a finding whose ruleId is null. Only an explicit ruleId/ruleIdPattern selector
        // requires a non-null id.
        val ruleId = this.ruleId
        return when {
            expectation.ruleId != null -> ruleId == expectation.ruleId
            expectation.ruleIdPattern != null ->
                ruleId != null && Regex(expectation.ruleIdPattern).containsMatchIn(ruleId)
            else -> true // no rule selector: scope only by uri/message (null ruleId still matches)
        }
    }

    private fun Result.matchesUri(expectation: Expectation): Boolean {
        val needle = expectation.uriContains ?: return true
        val uri =
            this.locations
                ?.firstOrNull()
                ?.physicalLocation
                ?.artifactLocation
                ?.uri
        return uri != null && uri.contains(needle)
    }

    private fun Result.matchesMessage(expectation: Expectation): Boolean {
        val needle = expectation.messageContains ?: return true
        val text = this.message?.text
        return text != null && text.contains(needle)
    }

    private fun matchesCount(
        spec: String,
        actual: Int,
    ): Boolean =
        when {
            spec.startsWith(">=") -> actual >= spec.removePrefix(">=").trim().toInt()
            spec.startsWith("==") -> actual == spec.removePrefix("==").trim().toInt()
            else -> error("unsupported count spec \"$spec\"")
        }
}

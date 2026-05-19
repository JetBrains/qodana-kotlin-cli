package org.jetbrains.qodana.engine.report

import org.jetbrains.qodana.core.model.QodanaYaml

object FailureThresholds {
    fun getFailureThresholds(
        yaml: QodanaYaml?,
        cliFailThreshold: String = "",
    ): Map<String, String> {
        if (yaml == null) return emptyMap()

        val result = mutableMapOf<String, String>()

        yaml.failThreshold?.let {
            result["any"] = it.toString()
        }

        val thresholds = yaml.failureConditions.severityThresholds
        thresholds.any?.let { result["any"] = it.toString() }
        thresholds.critical?.let { result["critical"] = it.toString() }
        thresholds.high?.let { result["high"] = it.toString() }
        thresholds.moderate?.let { result["moderate"] = it.toString() }
        thresholds.low?.let { result["low"] = it.toString() }
        thresholds.info?.let { result["info"] = it.toString() }

        if (cliFailThreshold.isNotEmpty()) {
            result.clear()
            result["any"] = cliFailThreshold
        }

        return result
    }

    fun thresholdsToArgs(thresholds: Map<String, String>): List<String> =
        thresholds.map { (severity, value) -> "--threshold-$severity=$value" }
}

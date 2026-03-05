package org.jetbrains.qodana.core.model

import java.nio.file.Path

data class ReportOptions(
    val outputFormats: Set<OutputFormat> = setOf(OutputFormat.SARIF),
    val baselinePath: Path? = null,
    val baselineIncludeAbsent: Boolean = false,
    val saveReport: Boolean = true,
    val showReport: Boolean = false,
) {
    enum class OutputFormat {
        SARIF,
        SARIF_AND_JSON,
        CODE_CLIMATE,
        BITBUCKET,
    }
}

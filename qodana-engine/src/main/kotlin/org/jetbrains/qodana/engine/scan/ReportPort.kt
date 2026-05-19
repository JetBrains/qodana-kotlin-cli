package org.jetbrains.qodana.engine.scan

object ReportPort {
    const val DEFAULT_PORT = 8080

    /**
     * Resolves the report port with precedence: showReportPort > port > default.
     */
    fun getShowReportPort(
        showReportPort: Int?,
        port: Int?,
        default: Int = DEFAULT_PORT,
    ): Int {
        if (showReportPort != null && showReportPort != 0) return showReportPort
        if (port != null && port != 0) return port
        return default
    }
}

package com.jetbrains.qodana.core.port

import java.nio.file.Path

interface ReportConverter {
    fun convertToHtml(resultsDir: Path, outputDir: Path)
}

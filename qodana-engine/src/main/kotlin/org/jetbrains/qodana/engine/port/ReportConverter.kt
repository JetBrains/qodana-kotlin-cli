package org.jetbrains.qodana.engine.port

import java.nio.file.Path

interface ReportConverter {
    fun convertToHtml(resultsDir: Path, outputDir: Path)
}

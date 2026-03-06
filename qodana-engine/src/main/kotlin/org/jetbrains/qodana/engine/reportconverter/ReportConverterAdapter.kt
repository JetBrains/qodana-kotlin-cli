package org.jetbrains.qodana.engine.reportconverter

import org.jetbrains.qodana.engine.port.ReportConverter
import org.jetbrains.qodana.sarif.QodanaReportConverter
import java.nio.file.Files
import java.nio.file.Path

class ReportConverterAdapter : ReportConverter {

    override fun convertToHtml(resultsDir: Path, outputDir: Path) {
        prepareOutputDir(outputDir)
        val options = QodanaReportConverter.Options(
            Integer.MAX_VALUE,
            resultsDir.toFile(),
            outputDir.toFile(),
        )
        QodanaReportConverter(options).convert()
    }

    internal fun prepareOutputDir(outputDir: Path) {
        val outputFile = outputDir.toFile()
        if (outputFile.exists() && !outputFile.deleteRecursively()) {
            error("Failed to clear report output directory: $outputDir")
        }
        Files.createDirectories(outputDir)
    }
}

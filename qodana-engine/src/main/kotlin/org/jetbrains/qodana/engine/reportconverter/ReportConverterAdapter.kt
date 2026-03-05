package org.jetbrains.qodana.engine.reportconverter

import org.jetbrains.qodana.engine.port.ReportConverter
import org.jetbrains.qodana.sarif.QodanaReportConverter
import java.nio.file.Path

class ReportConverterAdapter : ReportConverter {

    override fun convertToHtml(resultsDir: Path, outputDir: Path) {
        val options = QodanaReportConverter.Options(
            Integer.MAX_VALUE,
            resultsDir.toFile(),
            outputDir.toFile(),
        )
        QodanaReportConverter(options).convert()
    }
}

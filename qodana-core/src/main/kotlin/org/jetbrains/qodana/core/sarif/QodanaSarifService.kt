package org.jetbrains.qodana.core.sarif

import org.jetbrains.qodana.core.model.BaselineResult
import org.jetbrains.qodana.core.port.SarifService
import com.jetbrains.qodana.sarif.SarifUtil
import com.jetbrains.qodana.sarif.baseline.BaselineCalculation
import com.jetbrains.qodana.sarif.model.SarifReport
import java.nio.file.Path

class QodanaSarifService : SarifService {

    override fun read(path: Path): Any {
        return SarifUtil.readReport(path)
    }

    override fun write(path: Path, report: Any) {
        require(report is SarifReport) { "Expected SarifReport but got ${report::class}" }
        SarifUtil.writeReport(path, report)
    }

    override fun merge(reports: List<Path>, output: Path) {
        if (reports.isEmpty()) return
        val first = SarifUtil.readReport(reports.first())
        for (i in 1 until reports.size) {
            val other = SarifUtil.readReport(reports[i])
            for (run in other.runs ?: emptyList()) {
                first.runs = (first.runs ?: mutableListOf()).apply { add(run) }
            }
        }
        SarifUtil.writeReport(output, first)
    }

    override fun baselineCompare(report: Path, baseline: Path, includeAbsent: Boolean): BaselineResult {
        val reportData = SarifUtil.readReport(report)
        val baselineData = SarifUtil.readReport(baseline)
        val options = BaselineCalculation.Options(includeAbsent)
        val result = BaselineCalculation.compare(reportData, baselineData, options)
        return BaselineResult(
            newCount = result.newResults,
            unchangedCount = result.unchangedResults,
            absentCount = result.absentResults,
        )
    }

    override fun normalizePaths(reportPath: Path, projectDir: Path) {
        val report = SarifUtil.readReport(reportPath)
        val projectDirPrefix = projectDir.toString().replace("\\", "/").let {
            if (it.endsWith("/")) it else "$it/"
        }
        for (run in report.runs ?: emptyList()) {
            for (result in run.results ?: emptyList()) {
                for (location in result.locations ?: emptyList()) {
                    val uri = location.physicalLocation?.artifactLocation?.uri ?: continue
                    val normalized = uri.replace("\\", "/").removePrefix(projectDirPrefix)
                    location.physicalLocation.artifactLocation.uri = normalized
                }
            }
        }
        SarifUtil.writeReport(reportPath, report)
    }
}

package org.jetbrains.qodana.engine.report

import org.jetbrains.qodana.core.model.BaselineResult
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.engine.model.ReportOptions
import org.jetbrains.qodana.engine.port.ReportConverter
import org.jetbrains.qodana.core.port.SarifService
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReportProcessorTest {

    @Test
    fun `no problems returns SUCCESS`() {
        val sarif = FakeSarifService(problemCount = 0)
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        val result = processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(),
        )

        assertEquals(0, result.totalProblems)
        assertEquals(ExitCode.SUCCESS, result.exitCode)
    }

    @Test
    fun `problems without threshold returns THRESHOLD_REACHED`() {
        val sarif = FakeSarifService(problemCount = 5)
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        val result = processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(),
        )

        assertEquals(5, result.totalProblems)
        assertEquals(ExitCode.THRESHOLD_REACHED, result.exitCode)
    }

    @Test
    fun `problems below failThreshold returns THRESHOLD_REACHED`() {
        val sarif = FakeSarifService(problemCount = 3)
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        val result = processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(),
            failThreshold = 10,
        )

        assertEquals(ExitCode.THRESHOLD_REACHED, result.exitCode)
    }

    @Test
    fun `problems at failThreshold returns FAIL_THRESHOLD`() {
        val sarif = FakeSarifService(problemCount = 10)
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        val result = processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(),
            failThreshold = 10,
        )

        assertEquals(ExitCode.FAIL_THRESHOLD, result.exitCode)
    }

    @Test
    fun `baseline comparison uses new count for exit code`() {
        val sarif = FakeSarifService(
            problemCount = 10,
            baselineResult = BaselineResult(newCount = 0, unchangedCount = 10, absentCount = 0),
        )
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        val result = processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(baselinePath = Path.of("/baseline.sarif.json")),
        )

        assertEquals(10, result.totalProblems)
        assertEquals(0, result.newProblems)
        assertEquals(ExitCode.SUCCESS, result.exitCode)
        assertNotNull(result.baselineResult)
    }

    @Test
    fun `no baseline returns null baselineResult`() {
        val sarif = FakeSarifService(problemCount = 2)
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        val result = processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(),
        )

        assertNull(result.baselineResult)
        assertEquals(2, result.newProblems)
    }

    @Test
    fun `saveReport triggers converter`() {
        val sarif = FakeSarifService(problemCount = 0)
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(saveReport = true),
        )

        assertEquals(1, converter.convertCallCount)
    }

    @Test
    fun `saveReport false does not trigger converter`() {
        val sarif = FakeSarifService(problemCount = 0)
        val converter = FakeReportConverter()
        val processor = ReportProcessor(sarif, converter)

        processor.process(
            resultsDir = Path.of("/results"),
            reportDir = Path.of("/report"),
            reportOptions = ReportOptions(saveReport = false),
        )

        assertEquals(0, converter.convertCallCount)
    }
}

private class FakeSarifService(
    private val problemCount: Int = 0,
    private val baselineResult: BaselineResult? = null,
) : SarifService {
    override fun read(path: Path): Any {
        val results = (1..problemCount).map { mapOf("ruleId" to "R$it", "message" to mapOf("text" to "msg")) }
        return mapOf("runs" to listOf(mapOf("results" to results)))
    }

    override fun write(path: Path, report: Any) {}

    override fun merge(reports: List<Path>, output: Path) {}

    override fun baselineCompare(report: Path, baseline: Path, includeAbsent: Boolean): BaselineResult {
        return baselineResult ?: BaselineResult(newCount = problemCount, unchangedCount = 0, absentCount = 0)
    }

    override fun normalizePaths(reportPath: Path, projectDir: Path) {}
}

private class FakeReportConverter : ReportConverter {
    var convertCallCount = 0
    override fun convertToHtml(resultsDir: Path, outputDir: Path) {
        convertCallCount++
    }
}

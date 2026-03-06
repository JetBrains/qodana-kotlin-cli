package org.jetbrains.qodana.engine.report

import com.jetbrains.qodana.sarif.SarifUtil
import org.jetbrains.qodana.core.model.BaselineResult
import org.jetbrains.qodana.core.port.SarifService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class CodeClimateExporterTest {

    private val sarifWithResults = """
        {
          "version": "2.1.0",
          "runs": [{
            "tool": {"driver": {"name": "Qodana", "version": "1"}},
            "results": [
              {
                "ruleId": "TEST001",
                "level": "error",
                "message": {"text": "Found issue"},
                "partialFingerprints": {"equalIndicator/v2": "fp-1"},
                "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/Main.kt"}, "region": {"startLine": 10}}}]
              },
              {
                "ruleId": "TEST002",
                "level": "warning",
                "message": {"text": "Another issue"},
                "partialFingerprints": {"equalIndicator/v2": "fp-2"},
                "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/Util.kt"}, "region": {"startLine": 5}}}]
              }
            ]
          }]
        }
    """.trimIndent()

    @Test
    fun `export creates gitlab code quality json`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("qodana.sarif.json"), sarifWithResults)

        val exporter = CodeClimateExporter(TypedSarifService())
        exporter.export(dir)

        val output = dir.resolve("gl-code-quality-report.json")
        assertTrue(Files.exists(output), "gl-code-quality-report.json should be created")

        val content = Files.readString(output)
        assertTrue(content.contains("TEST001"))
        assertTrue(content.contains("TEST002"))
        assertTrue(content.contains("src/Main.kt"))
        assertTrue(content.contains("fp-1"))
        assertTrue(content.contains("fp-2"))
        assertTrue(!content.contains("\"type\""), "Go parity output should not include synthetic type field")
    }

    @Test
    fun `export maps sarif and qodana severities`(@TempDir dir: Path) {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana", "version": "1"}},
                "results": [
                  {"ruleId": "R1", "level": "error", "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f1"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 1}}}]},
                  {"ruleId": "R2", "level": "warning", "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f2"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 2}}}]},
                  {"ruleId": "R3", "level": "note", "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f3"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 3}}}]},
                  {"ruleId": "R4", "level": "warning", "message": {"text": "m"}, "properties": {"qodanaSeverity": "critical"}, "partialFingerprints": {"equalIndicator/v2": "f4"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 4}}}]},
                  {"ruleId": "R5", "level": "warning", "message": {"text": "m"}, "properties": {"qodanaSeverity": "moderate"}, "partialFingerprints": {"equalIndicator/v2": "f5"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 5}}}]},
                  {"ruleId": "R6", "level": "warning", "message": {"text": "m"}, "properties": {"qodanaSeverity": "info"}, "partialFingerprints": {"equalIndicator/v2": "f6"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 6}}}]}
                ]
              }]
            }
        """.trimIndent()

        Files.writeString(dir.resolve("qodana.sarif.json"), sarif)

        val exporter = CodeClimateExporter(TypedSarifService())
        exporter.export(dir)

        val content = Files.readString(dir.resolve("gl-code-quality-report.json"))
        assertTrue(content.contains("critical"))
        assertTrue(content.contains("major"))
        assertTrue(content.contains("minor"))
        assertTrue(content.contains("blocker"))
        assertTrue(content.contains("\"severity\" : \"info\""))
    }

    @Test
    fun `export skips unchanged and results without locations`(@TempDir dir: Path) {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana", "version": "1"}},
                "results": [
                  {"ruleId": "R1", "level": "error", "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f1"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 1}}}]},
                  {"ruleId": "R2", "level": "error", "baselineState": "unchanged", "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f2"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 2}}}]},
                  {"ruleId": "R3", "level": "error", "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f3"}, "locations": []}
                ]
              }]
            }
        """.trimIndent()

        Files.writeString(dir.resolve("qodana.sarif.json"), sarif)

        val exporter = CodeClimateExporter(TypedSarifService())
        exporter.export(dir)

        val content = Files.readString(dir.resolve("gl-code-quality-report.json"))
        assertTrue(content.contains("f1"))
        assertTrue(!content.contains("f2"))
        assertTrue(!content.contains("f3"))
    }

    @Test
    fun `export handles empty results`(@TempDir dir: Path) {
        val sarif = """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"Qodana","version":"1"}},"results":[]}]}"""
        Files.writeString(dir.resolve("qodana.sarif.json"), sarif)

        val exporter = CodeClimateExporter(TypedSarifService())
        exporter.export(dir)

        val content = Files.readString(dir.resolve("gl-code-quality-report.json"))
        assertTrue(content.contains("[]") || content.contains("[ ]"))
    }
}

private class TypedSarifService : SarifService {
    override fun read(path: Path): Any = SarifUtil.readReport(path)
    override fun write(path: Path, report: Any) {}
    override fun merge(reports: List<Path>, output: Path) {}
    override fun baselineCompare(report: Path, baseline: Path, includeAbsent: Boolean) =
        BaselineResult(0, 0, 0)
    override fun normalizePaths(reportPath: Path, projectDir: Path) {}
}

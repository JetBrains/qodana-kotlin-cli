package org.jetbrains.qodana.engine.report

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
            "tool": {"driver": {"name": "T", "version": "1"}},
            "results": [
              {
                "ruleId": "TEST001",
                "level": "error",
                "message": {"text": "Found issue"},
                "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/Main.kt"}, "region": {"startLine": 10}}}]
              },
              {
                "ruleId": "TEST002",
                "level": "warning",
                "message": {"text": "Another issue"},
                "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/Util.kt"}, "region": {"startLine": 5}}}]
              }
            ]
          }]
        }
    """.trimIndent()

    @Test
    fun `export creates code-climate json`(@TempDir dir: Path) {
        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarifWithResults)

        val sarif = MapBasedSarifService()
        val exporter = CodeClimateExporter(sarif)
        exporter.export(dir)

        val output = dir.resolve("code-climate.json")
        assertTrue(Files.exists(output), "code-climate.json should be created")

        val content = Files.readString(output)
        assertTrue(content.contains("TEST001"))
        assertTrue(content.contains("TEST002"))
        assertTrue(content.contains("src/Main.kt"))
    }

    @Test
    fun `export maps severity levels`(@TempDir dir: Path) {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "T", "version": "1"}},
                "results": [
                  {"ruleId": "R1", "level": "error", "message": {"text": "m"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 1}}}]},
                  {"ruleId": "R2", "level": "warning", "message": {"text": "m"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 2}}}]},
                  {"ruleId": "R3", "level": "note", "message": {"text": "m"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 3}}}]}
                ]
              }]
            }
        """.trimIndent()

        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarif)

        val service = MapBasedSarifService()
        val exporter = CodeClimateExporter(service)
        exporter.export(dir)

        val content = Files.readString(dir.resolve("code-climate.json"))
        assertTrue(content.contains("critical"))
        assertTrue(content.contains("major"))
        assertTrue(content.contains("minor"))
    }

    @Test
    fun `export handles empty results`(@TempDir dir: Path) {
        val sarif = """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"T","version":"1"}},"results":[]}]}"""
        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarif)

        val service = MapBasedSarifService()
        val exporter = CodeClimateExporter(service)
        exporter.export(dir)

        val content = Files.readString(dir.resolve("code-climate.json"))
        assertTrue(content.contains("[]") || content.contains("[ ]"))
    }
}

/**
 * SarifService that reads SARIF as a Jackson Map (matching CodeClimateExporter's duck-typed approach).
 */
private class MapBasedSarifService : SarifService {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.module.kotlin.kotlinModule())

    override fun read(path: Path): Any = mapper.readValue(path.toFile(), Map::class.java)
    override fun write(path: Path, report: Any) {}
    override fun merge(reports: List<Path>, output: Path) {}
    override fun baselineCompare(report: Path, baseline: Path, includeAbsent: Boolean) =
        BaselineResult(0, 0, 0)
    override fun normalizePaths(reportPath: Path, projectDir: Path) {}
}

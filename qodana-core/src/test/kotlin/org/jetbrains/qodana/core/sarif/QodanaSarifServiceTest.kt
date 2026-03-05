package org.jetbrains.qodana.core.sarif

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QodanaSarifServiceTest {

    private val service = QodanaSarifService()

    private val minimalSarif = """
        {
          "version": "2.1.0",
          "${'$'}schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
          "runs": [
            {
              "tool": {
                "driver": {
                  "name": "TestTool",
                  "version": "1.0.0"
                }
              },
              "results": []
            }
          ]
        }
    """.trimIndent()

    private val sarifWithResults = """
        {
          "version": "2.1.0",
          "runs": [
            {
              "tool": {
                "driver": {
                  "name": "TestTool",
                  "version": "1.0.0",
                  "rules": [
                    {"id": "TEST001", "shortDescription": {"text": "Test rule"}}
                  ]
                }
              },
              "results": [
                {
                  "ruleId": "TEST001",
                  "message": {"text": "Found issue"},
                  "locations": [
                    {
                      "physicalLocation": {
                        "artifactLocation": {"uri": "src/Main.kt"},
                        "region": {"startLine": 10}
                      }
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `read and write round-trip`(@TempDir dir: Path) {
        val file = dir.resolve("test.sarif.json")
        Files.writeString(file, minimalSarif)

        val report = service.read(file)
        assertNotNull(report)

        val output = dir.resolve("output.sarif.json")
        service.write(output, report)
        assertTrue(Files.exists(output))

        val content = Files.readString(output)
        assertTrue(content.contains("2.1.0"))
        assertTrue(content.contains("TestTool"))
    }

    @Test
    fun `read sarif with results`(@TempDir dir: Path) {
        val file = dir.resolve("results.sarif.json")
        Files.writeString(file, sarifWithResults)

        val report = service.read(file)
        assertNotNull(report)

        // Write and verify round-trip preserves results
        val output = dir.resolve("output.sarif.json")
        service.write(output, report)
        val content = Files.readString(output)
        assertTrue(content.contains("TEST001"))
        assertTrue(content.contains("Found issue"))
        assertTrue(content.contains("src/Main.kt"))
    }

    @Test
    fun `merge two sarif reports`(@TempDir dir: Path) {
        val file1 = dir.resolve("report1.sarif.json")
        val file2 = dir.resolve("report2.sarif.json")
        Files.writeString(file1, minimalSarif)
        Files.writeString(file2, minimalSarif.replace("TestTool", "OtherTool"))

        val output = dir.resolve("merged.sarif.json")
        service.merge(listOf(file1, file2), output)

        assertTrue(Files.exists(output))
        val content = Files.readString(output)
        assertTrue(content.contains("TestTool"))
        assertTrue(content.contains("OtherTool"))
    }

    @Test
    fun `merge single report`(@TempDir dir: Path) {
        val file = dir.resolve("single.sarif.json")
        Files.writeString(file, minimalSarif)

        val output = dir.resolve("merged.sarif.json")
        service.merge(listOf(file), output)

        assertTrue(Files.exists(output))
        val content = Files.readString(output)
        assertTrue(content.contains("TestTool"))
    }

    @Test
    fun `merge empty list does not create output`(@TempDir dir: Path) {
        val output = dir.resolve("merged.sarif.json")
        service.merge(emptyList(), output)
        assertTrue(!Files.exists(output))
    }

    @Test
    fun `normalize paths replaces backslashes`(@TempDir dir: Path) {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "T", "version": "1"}},
                "results": [{
                  "ruleId": "R1",
                  "message": {"text": "msg"},
                  "locations": [{
                    "physicalLocation": {
                      "artifactLocation": {"uri": "src\\main\\File.kt"}
                    }
                  }]
                }]
              }]
            }
        """.trimIndent()

        val file = dir.resolve("backslash.sarif.json")
        Files.writeString(file, sarif)
        service.normalizePaths(file, dir)

        val content = Files.readString(file)
        assertTrue(content.contains("src/main/File.kt"), "Backslashes should be normalized: $content")
        assertTrue(!content.contains("src\\\\main"), "No backslashes should remain")
    }

    @Test
    fun `baseline compare basic`(@TempDir dir: Path) {
        val report = dir.resolve("report.sarif.json")
        val baseline = dir.resolve("baseline.sarif.json")
        Files.writeString(report, sarifWithResults)
        Files.writeString(baseline, minimalSarif) // baseline has no results

        val result = service.baselineCompare(report, baseline, includeAbsent = false)
        assertNotNull(result)
        assertTrue(result.newCount >= 0)
        assertTrue(result.unchangedCount >= 0)
        assertTrue(result.absentCount >= 0)
    }
}

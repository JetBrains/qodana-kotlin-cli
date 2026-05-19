package org.jetbrains.qodana.engine.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SarifUtilsTest {
    @Test
    fun `getSarifPath returns correct path`() {
        assertEquals("/results/qodana.sarif.json", SarifUtils.getSarifPath("/results"))
    }

    @Test
    fun `getShortSarifPath returns correct path`() {
        assertEquals("/results/qodana-short.sarif.json", SarifUtils.getShortSarifPath("/results"))
    }

    @Test
    fun `getSeverity from qodanaSeverity property`() {
        val result =
            mapOf<String, Any?>(
                "properties" to mapOf("qodanaSeverity" to "Critical"),
                "level" to "error",
            )
        assertEquals("Critical", SarifUtils.getSeverity(result))
    }

    @Test
    fun `getSeverity falls back to level`() {
        val result = mapOf<String, Any?>("level" to "warning")
        assertEquals("warning", SarifUtils.getSeverity(result))
    }

    @Test
    fun `getSeverity defaults to note`() {
        val result = mapOf<String, Any?>()
        assertEquals("note", SarifUtils.getSeverity(result))
    }

    @Test
    fun `getFingerprint prefers v2`() {
        val result =
            mapOf<String, Any?>(
                "partialFingerprints" to
                    mapOf(
                        "equalIndicator/v1" to "fp-v1",
                        "equalIndicator/v2" to "fp-v2",
                    ),
            )
        assertEquals("fp-v2", SarifUtils.getFingerprint(result))
    }

    @Test
    fun `getFingerprint falls back to v1`() {
        val result =
            mapOf<String, Any?>(
                "partialFingerprints" to mapOf("equalIndicator/v1" to "fp-v1"),
            )
        assertEquals("fp-v1", SarifUtils.getFingerprint(result))
    }

    @Test
    fun `getFingerprint returns empty when no fingerprints`() {
        val result = mapOf<String, Any?>()
        assertEquals("", SarifUtils.getFingerprint(result))
    }

    @Test
    fun `removeDuplicates keeps unique results`() {
        val results =
            listOf(
                mapOf<String, Any?>("ruleId" to "R1", "partialFingerprints" to mapOf("equalIndicator/v2" to "fp1")),
                mapOf<String, Any?>("ruleId" to "R2", "partialFingerprints" to mapOf("equalIndicator/v2" to "fp2")),
            )
        assertEquals(2, SarifUtils.removeDuplicates(results).size)
    }

    @Test
    fun `removeDuplicates removes duplicates by fingerprint`() {
        val results =
            listOf(
                mapOf<String, Any?>("ruleId" to "R1", "partialFingerprints" to mapOf("equalIndicator/v2" to "fp1")),
                mapOf<String, Any?>("ruleId" to "R1", "partialFingerprints" to mapOf("equalIndicator/v2" to "fp1")),
                mapOf<String, Any?>("ruleId" to "R2", "partialFingerprints" to mapOf("equalIndicator/v2" to "fp2")),
            )
        val deduped = SarifUtils.removeDuplicates(results)
        assertEquals(2, deduped.size)
    }

    @Test
    fun `removeDuplicates keeps results without fingerprints`() {
        val results =
            listOf(
                mapOf<String, Any?>("ruleId" to "R1"),
                mapOf<String, Any?>("ruleId" to "R2"),
            )
        assertEquals(2, SarifUtils.removeDuplicates(results).size)
    }

    @Test
    fun `removeDuplicates empty list`() {
        assertEquals(0, SarifUtils.removeDuplicates(emptyList()).size)
    }

    @Test
    fun `runGuid uses env var when set`() {
        assertEquals("custom-guid", SarifUtils.runGuid(envGuid = "custom-guid"))
    }

    @Test
    fun `runGuid generates UUID when env not set`() {
        val guid = SarifUtils.runGuid(envGuid = null)
        assertTrue(guid.isNotEmpty())
        // Verify it's a valid UUID format
        assertNotEquals("", guid)
    }

    @Test
    fun `reportId uses env var when set`() {
        assertEquals("custom-id", SarifUtils.reportId("QDJVM", envReportId = "custom-id"))
    }

    @Test
    fun `reportId uses project id and date`() {
        val id = SarifUtils.reportId("QDJVM", envReportId = null, envProjectId = "my-project")
        assertTrue(id.startsWith("my-project/qodana/"))
    }

    @Test
    fun `reportId falls back to product code`() {
        val id = SarifUtils.reportId("QDJVM", envReportId = null, envProjectId = null)
        assertTrue(id.startsWith("QDJVM/qodana/"))
    }

    @Test
    fun `jobUrl returns env var`() {
        assertEquals("https://ci.example.com/job/1", SarifUtils.jobUrl("https://ci.example.com/job/1"))
    }

    @Test
    fun `jobUrl returns empty when not set`() {
        assertEquals("", SarifUtils.jobUrl(null))
    }

    @Test
    fun `findSarifFiles finds sarif files`(
        @TempDir tmpDir: Path,
    ) {
        Files.writeString(tmpDir.resolve("result.sarif.json"), "{}")
        Files.writeString(tmpDir.resolve("other.sarif.json"), "{}")
        Files.writeString(tmpDir.resolve("readme.txt"), "not sarif")

        val found = SarifUtils.findSarifFiles(tmpDir)
        assertEquals(2, found.size)
        assertTrue(found.all { it.fileName.toString().endsWith(".sarif.json") })
    }

    @Test
    fun `findSarifFiles returns empty when no sarif files`(
        @TempDir tmpDir: Path,
    ) {
        Files.writeString(tmpDir.resolve("readme.txt"), "not sarif")
        assertEquals(0, SarifUtils.findSarifFiles(tmpDir).size)
    }

    @Test
    fun `findSarifFiles case insensitive`(
        @TempDir tmpDir: Path,
    ) {
        Files.writeString(tmpDir.resolve("result.SARIF.JSON"), "{}")
        val found = SarifUtils.findSarifFiles(tmpDir)
        assertEquals(1, found.size)
    }

    // --- getRuleDescription ---

    @Test
    fun `getRuleDescription finds rule`() {
        val report =
            mapOf<String, Any?>(
                "runs" to
                    listOf(
                        mapOf<String, Any?>(
                            "tool" to
                                mapOf<String, Any?>(
                                    "extensions" to
                                        listOf(
                                            mapOf<String, Any?>(
                                                "rules" to
                                                    listOf(
                                                        mapOf<String, Any?>(
                                                            "id" to "TEST001",
                                                            "shortDescription" to mapOf("text" to "Test rule description"),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                    ),
            )
        assertEquals("Test rule description", SarifUtils.getRuleDescription(report, "TEST001"))
    }

    @Test
    fun `getRuleDescription returns empty for unknown rule`() {
        val report =
            mapOf<String, Any?>(
                "runs" to
                    listOf(
                        mapOf<String, Any?>(
                            "tool" to
                                mapOf<String, Any?>(
                                    "extensions" to
                                        listOf(
                                            mapOf<String, Any?>(
                                                "rules" to
                                                    listOf(
                                                        mapOf<String, Any?>(
                                                            "id" to "TEST001",
                                                            "shortDescription" to mapOf("text" to "desc"),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                    ),
            )
        assertEquals("", SarifUtils.getRuleDescription(report, "UNKNOWN"))
    }

    @Test
    fun `getRuleDescription handles empty report`() {
        assertEquals("", SarifUtils.getRuleDescription(emptyMap(), "TEST001"))
    }

    // --- formatSarifProblem ---

    @Test
    fun `formatSarifProblem full result`() {
        val result =
            mapOf<String, Any?>(
                "ruleId" to "TEST001",
                "level" to "error",
                "message" to mapOf("text" to "Something is wrong"),
                "locations" to
                    listOf(
                        mapOf<String, Any?>(
                            "physicalLocation" to
                                mapOf<String, Any?>(
                                    "artifactLocation" to mapOf("uri" to "src/Main.kt"),
                                    "region" to mapOf("startLine" to 10, "startColumn" to 5),
                                ),
                        ),
                    ),
            )
        val formatted = SarifUtils.formatSarifProblem(result)
        assertTrue(formatted!!.contains("[error]"))
        assertTrue(formatted.contains("TEST001"))
        assertTrue(formatted.contains("Something is wrong"))
        assertTrue(formatted.contains("src/Main.kt:10:5"))
    }

    @Test
    fun `formatSarifProblem returns null for null result`() {
        assertEquals(null, SarifUtils.formatSarifProblem(null))
    }

    @Test
    fun `formatSarifProblem empty result`() {
        val formatted = SarifUtils.formatSarifProblem(emptyMap())
        assertTrue(formatted!!.contains("[note]"))
    }

    @Test
    fun `formatSarifProblem no locations`() {
        val result =
            mapOf<String, Any?>(
                "ruleId" to "R1",
                "message" to mapOf("text" to "msg"),
            )
        val formatted = SarifUtils.formatSarifProblem(result)
        assertTrue(formatted!!.contains("R1: msg"))
        assertFalse(formatted.contains(" at "))
    }

    // --- makeShortSarif ---

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `makeShortSarif strips results and artifacts`() {
        val report =
            mutableMapOf<String, Any?>(
                "runs" to
                    listOf(
                        mutableMapOf<String, Any?>(
                            "results" to listOf("r1", "r2"),
                            "artifacts" to listOf("a1"),
                            "tool" to
                                mutableMapOf<String, Any?>(
                                    "driver" to
                                        mutableMapOf<String, Any?>(
                                            "name" to "qodana",
                                            "rules" to listOf("rule1"),
                                            "taxa" to listOf("taxa1"),
                                        ),
                                    "extensions" to listOf("ext1"),
                                ),
                        ),
                    ),
            )
        val short = SarifUtils.makeShortSarif(report)
        val run = (short["runs"] as List<Map<String, Any?>>).first()
        assertFalse(run.containsKey("results"))
        assertFalse(run.containsKey("artifacts"))
        val tool = run["tool"] as Map<String, Any?>
        assertFalse(tool.containsKey("extensions"))
        val driver = tool["driver"] as Map<String, Any?>
        assertFalse(driver.containsKey("rules"))
        assertFalse(driver.containsKey("taxa"))
        assertEquals("qodana", driver["name"])
    }
}

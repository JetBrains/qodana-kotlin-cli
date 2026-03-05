package org.jetbrains.qodana.engine.cloud

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudVersionsTest {

    @Test
    fun `toCloudVersion parses valid version`() {
        val result = toCloudVersion("1.0")
        assertTrue(result.isSuccess)
        assertEquals(ApiVersion(1, 0), result.getOrThrow())
    }

    @Test
    fun `toCloudVersion parses higher version`() {
        val result = toCloudVersion("2.5")
        assertTrue(result.isSuccess)
        assertEquals(ApiVersion(2, 5), result.getOrThrow())
    }

    @Test
    fun `toCloudVersion fails on non-numeric`() {
        val result = toCloudVersion("abc.def")
        assertTrue(result.isFailure)
    }

    @Test
    fun `toCloudVersion fails on wrong format`() {
        val result = toCloudVersion("1.2.3")
        assertTrue(result.isFailure)
    }

    @Test
    fun `toCloudVersion fails on single number`() {
        val result = toCloudVersion("1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `selectSupportedVersion finds matching version`() {
        val descriptions = listOf(
            ApiVersionDescription("1.0", "https://api.v1"),
            ApiVersionDescription("2.0", "https://api.v2"),
        )
        assertEquals("https://api.v1", selectSupportedVersion(descriptions))
    }

    @Test
    fun `selectSupportedVersion returns empty when no match`() {
        val descriptions = listOf(
            ApiVersionDescription("2.0", "https://api.v2"),
            ApiVersionDescription("3.0", "https://api.v3"),
        )
        assertEquals("", selectSupportedVersion(descriptions))
    }

    @Test
    fun `selectSupportedVersion picks first match`() {
        val descriptions = listOf(
            ApiVersionDescription("1.0", "https://first"),
            ApiVersionDescription("1.1", "https://second"),
        )
        assertEquals("https://first", selectSupportedVersion(descriptions))
    }

    @Test
    fun `selectSupportedVersion empty list`() {
        assertEquals("", selectSupportedVersion(emptyList()))
    }

    @Test
    fun `extractVersions returns version strings`() {
        val descriptions = listOf(
            ApiVersionDescription("1.0", "https://a"),
            ApiVersionDescription("2.5", "https://b"),
        )
        assertEquals(listOf("1.0", "2.5"), extractVersions(descriptions))
    }

    @Test
    fun `ApiVersionMismatchError message`() {
        val error = ApiVersionMismatchError("cloud", listOf("2.0", "3.0"))
        assertTrue(error.message!!.contains("cloud"))
        assertTrue(error.message!!.contains("[2.0, 3.0]"))
        assertTrue(error.message!!.contains("Required major version: 1"))
    }

    @Test
    fun `getCloudTeamsPageUrl constructs URL`() {
        val url = getCloudTeamsPageUrl("https://qodana.cloud", "origin-value", "/path/to/project")
        assertEquals("https://qodana.cloud/?origin=origin-value&name=project", url)
    }

    @Test
    fun `parseProjectName extracts name`() {
        val result = parseProjectName("""{"name":"my-project","id":"123"}""")
        assertTrue(result.isSuccess)
        assertEquals("my-project", result.getOrThrow())
    }

    @Test
    fun `parseProjectName empty json`() {
        val result = parseProjectName("{}")
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrThrow())
    }

    @Test
    fun `parseProjectName invalid json`() {
        val result = parseProjectName("not json")
        assertTrue(result.isFailure)
    }

    @Test
    fun `getReportUrl from open-in-ide json`(@TempDir dir: Path) {
        val file = dir.resolve("open-in-ide.json").toFile()
        file.writeText("""{"cloud":{"url":"https://qodana.cloud/report/123"}}""")
        assertEquals("https://qodana.cloud/report/123", getReportUrl(dir.toString()))
    }

    @Test
    fun `getReportUrl returns empty when file missing`(@TempDir dir: Path) {
        assertEquals("", getReportUrl(dir.toString()))
    }

    @Test
    fun `parseRawUrl adds https scheme`() {
        val result = parseRawUrl("qodana.cloud")
        assertTrue(result.isSuccess)
        assertEquals("https://qodana.cloud", result.getOrThrow())
    }

    @Test
    fun `parseRawUrl preserves http scheme`() {
        val result = parseRawUrl("http://localhost:8080")
        assertTrue(result.isSuccess)
        assertEquals("http://localhost:8080", result.getOrThrow())
    }

    @Test
    fun `parseRawUrl strips path`() {
        val result = parseRawUrl("https://qodana.cloud/some/path")
        assertTrue(result.isSuccess)
        assertEquals("https://qodana.cloud", result.getOrThrow())
    }

    @Test
    fun `parseRawUrl with port`() {
        val result = parseRawUrl("https://localhost:9090/api")
        assertTrue(result.isSuccess)
        assertEquals("https://localhost:9090", result.getOrThrow())
    }

    @Test
    fun `parseRawUrl empty fails`() {
        assertTrue(parseRawUrl("").isFailure)
    }

    @Test
    fun `parseRawUrl blank fails`() {
        assertTrue(parseRawUrl("   ").isFailure)
    }
}

package org.jetbrains.qodana.engine.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.BaselineResult
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitBucketExporterTest {

    private val mapper = ObjectMapper().registerModule(kotlinModule())

    private val sarifWithTwoResults = """
        {
          "version": "2.1.0",
          "runs": [{
            "tool": {"driver": {"name": "Qodana", "version": "1"}},
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

    private val sarifEmpty = """
        {
          "version": "2.1.0",
          "runs": [{
            "tool": {"driver": {"name": "Qodana", "version": "1"}},
            "results": []
          }]
        }
    """.trimIndent()

    @Test
    fun `export with results posts report and annotations`(@TempDir dir: Path) = runTest {
        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarifWithTwoResults)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(sarifPath), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "myws",
            repoSlug = "myrepo",
            commitHash = "abc123",
            token = "tok",
        )

        assertEquals(2, http.posts.size, "Expected 1 report POST + 1 annotations POST")

        // First POST: create report
        val reportPost = http.posts[0]
        val reportBody = mapper.readValue(reportPost.body, Map::class.java)
        assertEquals("FAILED", reportBody["result"])
        assertTrue(reportPost.url.endsWith("/reports/qodana"))

        // Second POST: annotations
        val annotationsPost = http.posts[1]
        val annotations = mapper.readValue(annotationsPost.body, List::class.java)
        assertEquals(2, annotations.size)
        assertTrue(annotationsPost.url.endsWith("/reports/qodana/annotations"))
    }

    @Test
    fun `export with no results posts PASSED report`(@TempDir dir: Path) = runTest {
        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarifEmpty)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(sarifPath), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "myws",
            repoSlug = "myrepo",
            commitHash = "abc123",
            token = "tok",
        )

        assertEquals(1, http.posts.size, "Expected only 1 report POST, no annotations POST")

        val reportBody = mapper.readValue(http.posts[0].body, Map::class.java)
        assertEquals("PASSED", reportBody["result"])
    }

    @Test
    fun `severity mapping`(@TempDir dir: Path) = runTest {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana", "version": "1"}},
                "results": [
                  {"ruleId": "R1", "level": "error",   "message": {"text": "m"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 1}}}]},
                  {"ruleId": "R2", "level": "warning", "message": {"text": "m"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 2}}}]},
                  {"ruleId": "R3", "level": "note",    "message": {"text": "m"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 3}}}]},
                  {"ruleId": "R4", "level": "none",    "message": {"text": "m"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 4}}}]}
                ]
              }]
            }
        """.trimIndent()

        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarif)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(sarifPath), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "ws",
            repoSlug = "repo",
            commitHash = "hash",
            token = "tok",
        )

        // Annotations are in the second POST
        @Suppress("UNCHECKED_CAST")
        val annotations = mapper.readValue(http.posts[1].body, List::class.java) as List<Map<String, Any>>
        val severities = annotations.map { it["severity"] }
        assertEquals(listOf("CRITICAL", "HIGH", "MEDIUM", "LOW"), severities)
    }

    @Test
    fun `annotations are batched in chunks of 100`(@TempDir dir: Path) = runTest {
        val results = (1..150).map { i ->
            """{"ruleId": "R$i", "level": "warning", "message": {"text": "issue $i"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "file$i.kt"}, "region": {"startLine": $i}}}]}"""
        }.joinToString(",\n")

        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana", "version": "1"}},
                "results": [$results]
              }]
            }
        """.trimIndent()

        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarif)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(sarifPath), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "ws",
            repoSlug = "repo",
            commitHash = "hash",
            token = "tok",
        )

        // 1 report POST + 2 annotation POSTs (100 + 50)
        assertEquals(3, http.posts.size, "Expected 1 report POST + 2 annotation batch POSTs")

        val batch1 = mapper.readValue(http.posts[1].body, List::class.java)
        val batch2 = mapper.readValue(http.posts[2].body, List::class.java)
        assertEquals(100, batch1.size)
        assertEquals(50, batch2.size)
    }

    @Test
    fun `auth header is Bearer token`(@TempDir dir: Path) = runTest {
        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarifWithTwoResults)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(sarifPath), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "ws",
            repoSlug = "repo",
            commitHash = "hash",
            token = "my-secret-token",
        )

        http.posts.forEach { post ->
            assertEquals("Bearer my-secret-token", post.headers["Authorization"])
        }
    }

    @Test
    fun `URL construction`(@TempDir dir: Path) = runTest {
        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, sarifWithTwoResults)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(sarifPath), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "myworkspace",
            repoSlug = "myrepo",
            commitHash = "deadbeef",
            token = "tok",
            reportId = "custom-report",
        )

        val expectedBase = "https://api.bitbucket.org/2.0/repositories/myworkspace/myrepo/commit/deadbeef/reports"
        assertEquals("$expectedBase/custom-report", http.posts[0].url)
        assertEquals("$expectedBase/custom-report/annotations", http.posts[1].url)
    }
}

private class BitBucketFakeSarifService(private val sarifPath: Path) : SarifService {
    private val mapper = ObjectMapper().registerModule(kotlinModule())

    override fun read(path: Path): Any = mapper.readValue(sarifPath.toFile(), Map::class.java)
    override fun write(path: Path, report: Any) {}
    override fun merge(reports: List<Path>, output: Path) {}
    override fun baselineCompare(report: Path, baseline: Path, includeAbsent: Boolean) =
        BaselineResult(0, 0, 0)
    override fun normalizePaths(reportPath: Path, projectDir: Path) {}
}

private class RecordingHttp : HttpTransport {
    data class PostCall(
        val url: String,
        val body: String,
        val contentType: String,
        val headers: Map<String, String>,
    )

    val posts = mutableListOf<PostCall>()

    override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, "")

    override suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse {
        posts.add(PostCall(url, String(body), contentType, headers))
        return HttpResponse(200, "")
    }

    override suspend fun download(url: String, target: Path, headers: Map<String, String>) {}

    override suspend fun uploadMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String>,
    ) = HttpResponse(200, "")
}

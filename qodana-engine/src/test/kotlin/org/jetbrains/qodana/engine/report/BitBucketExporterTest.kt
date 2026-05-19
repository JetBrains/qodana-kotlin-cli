package org.jetbrains.qodana.engine.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.jetbrains.qodana.sarif.SarifUtil
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

    private val sarifWithTwoResults =
        """
        {
          "version": "2.1.0",
          "runs": [{
            "tool": {
              "driver": {"name": "Qodana", "fullName": "Qodana for JVM", "version": "1"},
              "extensions": [{
                "name": "rules",
                "rules": [
                  {"id": "TEST001", "shortDescription": {"text": "Rule 1 detail"}},
                  {"id": "TEST002", "shortDescription": {"text": "Rule 2 detail"}}
                ]
              }]
            },
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

    private val sarifEmpty =
        """
        {
          "version": "2.1.0",
          "runs": [{
            "tool": {"driver": {"name": "Qodana", "version": "1"}},
            "results": []
          }]
        }
        """.trimIndent()

    @Test
    fun `export with results posts report and annotations`(
        @TempDir dir: Path,
    ) = runTest {
        Files.writeString(dir.resolve("qodana.sarif.json"), sarifWithTwoResults)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "myws",
            repoSlug = "myrepo",
            commitHash = "abc123",
            token = "tok",
        )

        assertEquals(2, http.posts.size, "Expected 1 report POST + 1 annotations POST")

        val reportBody = mapper.readValue(http.posts[0].body, Map::class.java)
        assertEquals("FAILED", reportBody["result"])
        assertEquals("JetBrains Qodana", reportBody["reporter"])
        assertEquals("Found 2 new problems according to the checks applied", reportBody["details"])
        assertEquals("Qodana for JVM ", reportBody["title"])
        assertTrue(http.posts[0].url.endsWith("/reports/qodana"))

        @Suppress("UNCHECKED_CAST")
        val annotations = mapper.readValue(http.posts[1].body, List::class.java) as List<Map<String, Any>>
        assertEquals(2, annotations.size)
        assertTrue(http.posts[1].url.endsWith("/reports/qodana/annotations"))
        assertEquals("CODE_SMELL", annotations[0]["annotation_type"])
        assertEquals("fp-1", annotations[0]["external_id"])
        assertEquals("TEST001: Found issue", annotations[0]["summary"])
        assertEquals("Rule 1 detail", annotations[0]["details"])
        assertEquals("HIGH", annotations[0]["severity"])
    }

    @Test
    fun `export with no results posts PASSED report`(
        @TempDir dir: Path,
    ) = runTest {
        Files.writeString(dir.resolve("qodana.sarif.json"), sarifEmpty)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(), http)

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
        assertEquals("It seems all right 👌 No new problems found according to the checks applied", reportBody["details"])
    }

    @Test
    fun `severity mapping uses qodanaSeverity and sarif level`(
        @TempDir dir: Path,
    ) = runTest {
        val sarif =
            """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana", "version": "1"}},
                "results": [
                  {"ruleId": "R1", "level": "error",   "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f1"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 1}}}]},
                  {"ruleId": "R2", "level": "warning", "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f2"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 2}}}]},
                  {"ruleId": "R3", "level": "note",    "message": {"text": "m"}, "partialFingerprints": {"equalIndicator/v2": "f3"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 3}}}]},
                  {"ruleId": "R4", "level": "note",    "message": {"text": "m"}, "properties": {"qodanaSeverity": "info"}, "partialFingerprints": {"equalIndicator/v2": "f4"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 4}}}]},
                  {"ruleId": "R5", "level": "warning", "message": {"text": "m"}, "properties": {"qodanaSeverity": "critical"}, "partialFingerprints": {"equalIndicator/v2": "f5"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 5}}}]}
                ]
              }]
            }
            """.trimIndent()

        Files.writeString(dir.resolve("qodana.sarif.json"), sarif)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "ws",
            repoSlug = "repo",
            commitHash = "hash",
            token = "tok",
        )

        @Suppress("UNCHECKED_CAST")
        val annotations = mapper.readValue(http.posts[1].body, List::class.java) as List<Map<String, Any>>
        val severities = annotations.map { it["severity"] }
        assertEquals(listOf("HIGH", "MEDIUM", "LOW", "INFO", "HIGH"), severities)
    }

    @Test
    fun `export skips unchanged and results without locations`(
        @TempDir dir: Path,
    ) = runTest {
        val sarif =
            """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana", "version": "1"}},
                "results": [
                  {"ruleId": "R1", "level": "error", "message": {"text": "a"}, "partialFingerprints": {"equalIndicator/v2": "f1"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 1}}}]},
                  {"ruleId": "R2", "level": "error", "baselineState": "unchanged", "message": {"text": "b"}, "partialFingerprints": {"equalIndicator/v2": "f2"}, "locations": [{"physicalLocation": {"artifactLocation": {"uri": "f"}, "region": {"startLine": 2}}}]},
                  {"ruleId": "R3", "level": "error", "message": {"text": "c"}, "partialFingerprints": {"equalIndicator/v2": "f3"}, "locations": []}
                ]
              }]
            }
            """.trimIndent()
        Files.writeString(dir.resolve("qodana.sarif.json"), sarif)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "ws",
            repoSlug = "repo",
            commitHash = "hash",
            token = "tok",
        )

        @Suppress("UNCHECKED_CAST")
        val annotations = mapper.readValue(http.posts[1].body, List::class.java) as List<Map<String, Any>>
        assertEquals(1, annotations.size)
        assertEquals("f1", annotations[0]["external_id"])
    }

    @Test
    fun `annotations are capped to 1000 and batched by 100`(
        @TempDir dir: Path,
    ) = runTest {
        val results =
            (1..1200).joinToString(",") { i ->
                """{"ruleId":"R$i","level":"warning","message":{"text":"issue $i"},"partialFingerprints":{"equalIndicator/v2":"fp-$i"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":"file$i.kt"},"region":{"startLine":$i}}}]}"""
            }
        val sarif = """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"Qodana","version":"1"}},"results":[$results]}]}"""
        Files.writeString(dir.resolve("qodana.sarif.json"), sarif)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(), http)

        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://api.bitbucket.org",
            workspace = "ws",
            repoSlug = "repo",
            commitHash = "hash",
            token = "tok",
        )

        assertEquals(11, http.posts.size, "1 report + 10 annotation requests")
        for (i in 1..10) {
            val batch = mapper.readValue(http.posts[i].body, List::class.java)
            assertEquals(100, batch.size)
        }
    }

    @Test
    fun `auth header is Bearer token`(
        @TempDir dir: Path,
    ) = runTest {
        Files.writeString(dir.resolve("qodana.sarif.json"), sarifWithTwoResults)

        val http = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(), http)

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
    fun `URL construction supports cloud and self-hosted base urls`(
        @TempDir dir: Path,
    ) = runTest {
        Files.writeString(dir.resolve("qodana.sarif.json"), sarifWithTwoResults)

        val cloudHttp = RecordingHttp()
        val exporter = BitBucketExporter(BitBucketFakeSarifService(), cloudHttp)
        exporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://bitbucket.org/myworkspace/myrepo",
            workspace = "myworkspace",
            repoSlug = "myrepo",
            commitHash = "deadbeef",
            token = "tok",
            reportId = "custom-report",
        )
        val cloudBase = "https://api.bitbucket.org/2.0/repositories/myworkspace/myrepo/commit/deadbeef/reports"
        assertEquals("$cloudBase/custom-report", cloudHttp.posts[0].url)

        val selfHostedHttp = RecordingHttp()
        val selfHostedExporter = BitBucketExporter(BitBucketFakeSarifService(), selfHostedHttp)
        selfHostedExporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://bitbucket.example.com/scm/PROJ/repo",
            workspace = "myworkspace",
            repoSlug = "myrepo",
            commitHash = "deadbeef",
            token = "tok",
            reportId = "custom-report",
        )
        val selfHostedBase = "https://bitbucket.example.com/rest/api/1.0/repositories/myworkspace/myrepo/commit/deadbeef/reports"
        assertEquals("$selfHostedBase/custom-report", selfHostedHttp.posts[0].url)

        val pipelineHttp = RecordingHttp()
        val pipelineEnv = mapOf("BITBUCKET_PIPELINE_UUID" to "{1234-uuid}")
        val pipelineExporter =
            BitBucketExporter(
                sarifService = BitBucketFakeSarifService(),
                http = pipelineHttp,
                getEnv = { key -> pipelineEnv[key] },
            )
        pipelineExporter.export(
            resultsDir = dir,
            bitbucketUrl = "https://bitbucket.example.com/scm/PROJ/repo",
            workspace = "myworkspace",
            repoSlug = "myrepo",
            commitHash = "deadbeef",
            token = "tok",
            reportId = "custom-report",
        )
        val pipelineBase = "http://api.bitbucket.org/2.0/repositories/myworkspace/myrepo/commit/deadbeef/reports"
        assertEquals("$pipelineBase/custom-report", pipelineHttp.posts[0].url)
    }
}

private class BitBucketFakeSarifService : SarifService {
    override fun read(path: Path): Any = SarifUtil.readReport(path)

    override fun write(
        path: Path,
        report: Any,
    ) {}

    override fun merge(
        reports: List<Path>,
        output: Path,
    ) {}

    override fun baselineCompare(
        report: Path,
        baseline: Path,
        includeAbsent: Boolean,
    ) = BaselineResult(0, 0, 0)

    override fun normalizePaths(
        reportPath: Path,
        projectDir: Path,
    ) {}
}

private class RecordingHttp : HttpTransport {
    data class PostCall(
        val url: String,
        val body: String,
        val contentType: String,
        val headers: Map<String, String>,
    )

    val posts = mutableListOf<PostCall>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ) = HttpResponse(200, "")

    override suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse {
        posts.add(PostCall(url, String(body), contentType, headers))
        return HttpResponse(200, "")
    }

    override suspend fun download(
        url: String,
        target: Path,
        headers: Map<String, String>,
    ) {}

    override suspend fun uploadMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String>,
    ) = HttpResponse(200, "")
}

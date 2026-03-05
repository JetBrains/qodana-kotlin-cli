package org.jetbrains.qodana.engine.cloud

import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudClientTest {

    @Test
    fun `fetchEndpoints parses response`() = runTest {
        val http = FakeHttp(mapOf(
            "https://qodana.cloud/api/config.json" to HttpResponse(
                200,
                """{"LintersApiUrl":"https://linters.api","CloudApiUrl":"https://cloud.api"}"""
            )
        ))
        val client = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)

        val result = client.fetchEndpoints()
        assertTrue(result.isSuccess)
        assertEquals("https://linters.api", result.getOrThrow().lintersApiUrl)
        assertEquals("https://cloud.api", result.getOrThrow().cloudApiUrl)
    }

    @Test
    fun `fetchEndpoints trims trailing slash from endpoint`() = runTest {
        val http = FakeHttp(mapOf(
            "https://qodana.cloud/api/config.json" to HttpResponse(
                200,
                """{"LintersApiUrl":"https://l","CloudApiUrl":"https://c"}"""
            )
        ))
        val client = CloudClient(http, endpoint = "https://qodana.cloud/", token = "t", maxRetries = 1, cooldownMs = 0)

        assertTrue(client.fetchEndpoints().isSuccess)
    }

    @Test
    fun `fetchEndpoints returns failure on HTTP error`() = runTest {
        val http = FakeHttp(mapOf(
            "https://qodana.cloud/api/config.json" to HttpResponse(500, "Server Error")
        ))
        val client = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)

        val result = client.fetchEndpoints()
        assertTrue(result.isFailure)
    }

    @Test
    fun `getAuthenticated includes bearer token`() = runTest {
        val http = RecordingHttp(HttpResponse(200, """{"licenseId":"id","licenseKey":"key","expirationDate":"2026","projectIdHash":"p","organisationIdHash":"o","licensePlan":"plan"}"""))
        val client = CloudClient(http, endpoint = "https://qodana.cloud", token = "my-token", maxRetries = 1, cooldownMs = 0)

        client.getAuthenticated<LicenseData>("https://api/data")

        assertEquals("Bearer my-token", http.lastHeaders["Authorization"])
    }

    @Test
    fun `retries on failure up to maxRetries`() = runTest {
        var callCount = 0
        val http = object : FakeHttp(emptyMap()) {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
                callCount++
                return HttpResponse(500, "error")
            }
        }
        val client = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 3, cooldownMs = 0)

        val result = client.fetchEndpoints()
        assertTrue(result.isFailure)
        assertEquals(3, callCount)
    }

    @Test
    fun `succeeds on second retry`() = runTest {
        var callCount = 0
        val http = object : FakeHttp(emptyMap()) {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
                callCount++
                return if (callCount == 1) HttpResponse(500, "error")
                else HttpResponse(200, """{"LintersApiUrl":"l","CloudApiUrl":"c"}""")
            }
        }
        val client = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 3, cooldownMs = 0)

        val result = client.fetchEndpoints()
        assertTrue(result.isSuccess)
        assertEquals(2, callCount)
    }
}

private open class FakeHttp(private val responses: Map<String, HttpResponse>) : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        return responses[url] ?: HttpResponse(404, "Not Found")
    }
    override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>): HttpResponse {
        return HttpResponse(200, "")
    }
    override suspend fun download(url: String, target: Path, headers: Map<String, String>) {}
    override suspend fun uploadMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>): HttpResponse {
        return HttpResponse(200, "")
    }
}

private class RecordingHttp(private val response: HttpResponse) : HttpTransport {
    var lastHeaders: Map<String, String> = emptyMap()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastHeaders = headers
        return response
    }
    override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>): HttpResponse {
        return HttpResponse(200, "")
    }
    override suspend fun download(url: String, target: Path, headers: Map<String, String>) {}
    override suspend fun uploadMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>): HttpResponse {
        return HttpResponse(200, "")
    }
}

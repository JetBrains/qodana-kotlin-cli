package org.jetbrains.qodana.engine.cloud

import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.QodanaError
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseValidatorTest {

    private val validLicenseJson = """
        {
            "licenseId": "lic-123",
            "licenseKey": "key-abc",
            "expirationDate": "2026-12-31",
            "projectIdHash": "proj-hash",
            "organisationIdHash": "org-hash",
            "licensePlan": "ULTIMATE_PLUS"
        }
    """.trimIndent()

    private val endpointsJson = """
        {
          "api": {
            "versions": [
              {"version":"1.1","url":"https://cloud.api"}
            ]
          },
          "linters": {
            "versions": [
              {"version":"1.0","url":"https://linters.api"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `validate returns license data on success`() = runTest {
        val http = LicenseFakeHttp(mapOf(
            "https://qodana.cloud/api/versions" to HttpResponse(200, endpointsJson),
            "https://linters.api/linters/license-key" to HttpResponse(200, validLicenseJson),
        ))
        val cloudClient = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)
        val validator = LicenseValidator(http, cloudClient)

        val result = validator.validate("my-token")
        assertTrue(result.isSuccess)

        val license = result.getOrThrow()
        assertEquals("lic-123", license.licenseId)
        assertEquals("key-abc", license.licenseKey)
        assertEquals("ULTIMATE_PLUS", license.licensePlan)
    }

    @Test
    fun `validate returns auth error on 401`() = runTest {
        val http = LicenseFakeHttp(mapOf(
            "https://qodana.cloud/api/versions" to HttpResponse(200, endpointsJson),
            "https://linters.api/linters/license-key" to HttpResponse(401, "Unauthorized"),
        ))
        val cloudClient = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)
        val validator = LicenseValidator(http, cloudClient)

        val result = validator.validate("bad-token")
        assertTrue(result.isFailure)

        val error = (result.exceptionOrNull() as QodanaErrorException).error
        assertIs<QodanaError.Auth>(error)
    }

    @Test
    fun `validate returns auth error on 404`() = runTest {
        val http = LicenseFakeHttp(mapOf(
            "https://qodana.cloud/api/versions" to HttpResponse(200, endpointsJson),
            "https://linters.api/linters/license-key" to HttpResponse(404, "Not Found"),
        ))
        val cloudClient = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)
        val validator = LicenseValidator(http, cloudClient)

        val result = validator.validate("token")
        assertTrue(result.isFailure)

        val error = (result.exceptionOrNull() as QodanaErrorException).error
        assertIs<QodanaError.Auth>(error)
    }

    @Test
    fun `validate returns network error on 500`() = runTest {
        val http = LicenseFakeHttp(mapOf(
            "https://qodana.cloud/api/versions" to HttpResponse(200, endpointsJson),
            "https://linters.api/linters/license-key" to HttpResponse(500, "Server Error"),
        ))
        val cloudClient = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)
        val validator = LicenseValidator(http, cloudClient)

        val result = validator.validate("token")
        assertTrue(result.isFailure)

        val error = (result.exceptionOrNull() as QodanaErrorException).error
        assertIs<QodanaError.Network>(error)
    }

    @Test
    fun `validate fails when endpoints fetch fails`() = runTest {
        val http = LicenseFakeHttp(mapOf(
            "https://qodana.cloud/api/versions" to HttpResponse(500, "Server Error"),
        ))
        val cloudClient = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)
        val validator = LicenseValidator(http, cloudClient)

        val result = validator.validate("token")
        assertTrue(result.isFailure)
    }
}

private class LicenseFakeHttp(private val responses: Map<String, HttpResponse>) : HttpTransport {
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

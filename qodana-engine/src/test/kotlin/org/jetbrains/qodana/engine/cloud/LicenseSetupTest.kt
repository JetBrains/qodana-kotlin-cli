package org.jetbrains.qodana.engine.cloud

import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.QodanaError
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseSetupTest {
    private val endpointsJson =
        """
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

    private fun validLicenseJson(plan: String = "ULTIMATE_PLUS") =
        """
        {
            "licenseId": "lic-123",
            "licenseKey": "key-abc",
            "expirationDate": "2026-12-31",
            "projectIdHash": "proj-hash",
            "organisationIdHash": "org-hash",
            "licensePlan": "$plan"
        }
        """.trimIndent()

    private fun fakeValidator(licenseJson: String): LicenseValidator {
        val http =
            SetupFakeHttp(
                mapOf(
                    "https://qodana.cloud/api/versions" to HttpResponse(200, endpointsJson),
                    "https://linters.api/linters/license-key" to HttpResponse(200, licenseJson),
                ),
            )
        val cloudClient = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)
        return LicenseValidator(http, cloudClient)
    }

    private fun failingValidator(statusCode: Int): LicenseValidator {
        val http =
            SetupFakeHttp(
                mapOf(
                    "https://qodana.cloud/api/versions" to HttpResponse(200, endpointsJson),
                    "https://linters.api/linters/license-key" to HttpResponse(statusCode, "error"),
                ),
            )
        val cloudClient = CloudClient(http, endpoint = "https://qodana.cloud", token = "t", maxRetries = 1, cooldownMs = 0)
        return LicenseValidator(http, cloudClient)
    }

    @Test
    fun `community linter needs no license`() =
        runTest {
            val result =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = Linters.JVM_COMMUNITY,
                    licenseToken = LicenseToken.EMPTY,
                    validator = fakeValidator(validLicenseJson()),
                )
            assertTrue(result.isSuccess)
            assertEquals("", result.getOrThrow().licenseKey)
        }

    @Test
    fun `paid linter with no token fails`() =
        runTest {
            val result =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = Linters.JVM,
                    licenseToken = LicenseToken.EMPTY,
                    validator = fakeValidator(validLicenseJson()),
                )
            assertTrue(result.isFailure)
            val error = (result.exceptionOrNull() as QodanaErrorException).error
            assertIs<QodanaError.Auth>(error)
        }

    @Test
    fun `paid linter with valid token returns license`() =
        runTest {
            val result =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = Linters.JVM,
                    licenseToken = LicenseToken(token = "my-token", licenseOnly = false),
                    validator = fakeValidator(validLicenseJson()),
                )
            assertTrue(result.isSuccess)
            val setup = result.getOrThrow()
            assertEquals("key-abc", setup.licenseKey)
            assertEquals("proj-hash", setup.projectIdHash)
            assertEquals("org-hash", setup.organisationIdHash)
        }

    @Test
    fun `existing license skips cloud call`() =
        runTest {
            val result =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = Linters.JVM,
                    licenseToken = LicenseToken.EMPTY,
                    validator = failingValidator(500),
                    existingLicense = "already-have-key",
                )
            assertTrue(result.isSuccess)
            assertEquals("already-have-key", result.getOrThrow().licenseKey)
        }

    @Test
    fun `community plan rejects paid linter`() =
        runTest {
            val result =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = Linters.JVM,
                    licenseToken = LicenseToken(token = "my-token", licenseOnly = false),
                    validator = fakeValidator(validLicenseJson(plan = "COMMUNITY")),
                )
            assertTrue(result.isFailure)
            val error = (result.exceptionOrNull() as QodanaErrorException).error
            assertIs<QodanaError.Auth>(error)
            assertTrue(error.message.contains("Community"))
        }

    @Test
    fun `empty license key fails`() =
        runTest {
            val json =
                """
                {
                    "licenseId": "lic-123",
                    "licenseKey": "",
                    "expirationDate": "2026-12-31",
                    "projectIdHash": "ph",
                    "organisationIdHash": "oh",
                    "licensePlan": "ULTIMATE"
                }
                """.trimIndent()
            val result =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = Linters.JVM,
                    licenseToken = LicenseToken(token = "my-token", licenseOnly = false),
                    validator = fakeValidator(json),
                )
            assertTrue(result.isFailure)
            val error = (result.exceptionOrNull() as QodanaErrorException).error
            assertIs<QodanaError.Auth>(error)
        }

    @Test
    fun `allCommunityNames returns non-empty string`() {
        val names = LicenseSetup.allCommunityNames()
        assertTrue(names.contains("Community"))
        assertTrue(names.contains("\""))
    }

    @Test
    fun `auth error propagated from validator`() =
        runTest {
            val result =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = Linters.JVM,
                    licenseToken = LicenseToken(token = "bad-token", licenseOnly = false),
                    validator = failingValidator(401),
                )
            assertTrue(result.isFailure)
            val error = (result.exceptionOrNull() as QodanaErrorException).error
            assertIs<QodanaError.Auth>(error)
        }
}

private class SetupFakeHttp(
    private val responses: Map<String, HttpResponse>,
) : HttpTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse = responses[url] ?: HttpResponse(404, "Not Found")

    override suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse = HttpResponse(200, "")

    override suspend fun download(
        url: String,
        target: Path,
        headers: Map<String, String>,
    ) {}

    override suspend fun uploadMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String>,
    ): HttpResponse = HttpResponse(200, "")
}

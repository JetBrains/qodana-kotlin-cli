package org.jetbrains.qodana.engine.cloud

import org.jetbrains.qodana.core.model.QodanaError
import org.jetbrains.qodana.engine.port.HttpTransport
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

class LicenseValidator(
    private val http: HttpTransport,
    private val cloudClient: CloudClient,
) {
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Validates the license by fetching endpoints from the cloud and then
     * requesting the license key from the Linters API.
     *
     * Status code handling:
     * - 200: success, returns [LicenseData]
     * - 401, 404: token declined, returns [QodanaError.Auth]
     * - others: retried by [CloudClient], eventually returns [QodanaError.Network]
     */
    suspend fun validate(token: String): Result<LicenseData> {
        val endpoints = cloudClient.fetchEndpoints().getOrElse { e ->
            return Result.failure(e)
        }

        val url = "${endpoints.lintersApiUrl.trimEnd('/')}/linters/license-key"
        val headers = mapOf("Authorization" to "Bearer $token")
        val response = try {
            http.get(url, headers)
        } catch (e: Exception) {
            return Result.failure(
                QodanaErrorException(QodanaError.Network(url, e.message ?: "Unknown network error"))
            )
        }

        return when (response.statusCode) {
            200 -> {
                try {
                    Result.success(mapper.readValue<LicenseData>(response.body))
                } catch (e: Exception) {
                    Result.failure(
                        QodanaErrorException(
                            QodanaError.Network(url, "Failed to parse license response: ${e.message}")
                        )
                    )
                }
            }
            401, 404 -> {
                Result.failure(
                    QodanaErrorException(
                        QodanaError.Auth("Token declined (HTTP ${response.statusCode})")
                    )
                )
            }
            else -> {
                Result.failure(
                    QodanaErrorException(
                        QodanaError.Network(url, "Unexpected status ${response.statusCode}: ${response.body}")
                    )
                )
            }
        }
    }
}

package org.jetbrains.qodana.engine.cloud

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.QodanaError
import org.jetbrains.qodana.engine.port.HttpTransport

class CloudClient(
    private val http: HttpTransport,
    private val endpoint: String = System.getenv(QodanaEnv.ENDPOINT) ?: QodanaEnv.DEFAULT_ENDPOINT,
    private val token: String,
    private val maxRetries: Int = System.getenv(QodanaEnv.CLOUD_REQUEST_RETRIES)?.toIntOrNull() ?: DEFAULT_RETRIES,
    private val cooldownMs: Long = System.getenv(QodanaEnv.CLOUD_REQUEST_COOLDOWN)?.toLongOrNull() ?: DEFAULT_COOLDOWN_MS,
    private val timeoutMs: Long = System.getenv(QodanaEnv.CLOUD_REQUEST_TIMEOUT)?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_RETRIES = 3
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val VERSIONS_URI = "/api/versions"
    }

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(kotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun authHeaders(): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $token",
        )

    suspend fun fetchEndpoints(): Result<CloudEndpoints> {
        val url = "${endpoint.trimEnd('/')}$VERSIONS_URI"
        return executeWithRetries(url) { requestUrl ->
            val response = http.get(requestUrl)
            if (!response.isSuccess) {
                return@executeWithRetries Result.failure(
                    QodanaErrorException(
                        QodanaError.Network(requestUrl, "HTTP ${response.statusCode}: ${response.body}"),
                    ),
                )
            }

            val descriptions = mapper.readValue<ApiDescriptions>(response.body)
            val cloudApiUrl = selectSupportedVersion(descriptions.api.versions)
            if (cloudApiUrl.isBlank()) {
                return@executeWithRetries Result.failure(
                    ApiVersionMismatchError("cloud", extractVersions(descriptions.api.versions)),
                )
            }

            val lintersApiUrl = selectSupportedVersion(descriptions.linters.versions)
            if (lintersApiUrl.isBlank()) {
                return@executeWithRetries Result.failure(
                    ApiVersionMismatchError("linters", extractVersions(descriptions.linters.versions)),
                )
            }

            Result.success(
                CloudEndpoints(
                    lintersApiUrl = lintersApiUrl,
                    cloudApiUrl = cloudApiUrl,
                ),
            )
        }
    }

    suspend fun <T> getAuthenticated(
        url: String,
        responseType: Class<T>,
    ): Result<T> {
        return executeWithRetries(url) { requestUrl ->
            val response = http.get(requestUrl, authHeaders())
            if (!response.isSuccess) {
                return@executeWithRetries Result.failure(
                    QodanaErrorException(
                        QodanaError.Network(requestUrl, "HTTP ${response.statusCode}: ${response.body}"),
                    ),
                )
            }
            Result.success(mapper.readValue(response.body, responseType))
        }
    }

    suspend inline fun <reified T> getAuthenticated(url: String): Result<T> = getAuthenticated(url, T::class.java)

    private suspend fun <T> executeWithRetries(
        url: String,
        action: suspend (String) -> Result<T>,
    ): Result<T> {
        var lastException: Throwable? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        repeat(maxRetries) { attempt ->
            if (System.currentTimeMillis() > deadline) {
                return Result.failure(
                    lastException ?: QodanaErrorException(
                        QodanaError.Network(url, "Request timed out after ${timeoutMs}ms"),
                    ),
                )
            }
            try {
                val result = action(url)
                if (result.isSuccess) return result
                lastException = result.exceptionOrNull()
            } catch (e: Exception) {
                lastException = e
            }
            if (attempt < maxRetries - 1) {
                kotlinx.coroutines.delay(cooldownMs)
            }
        }
        return Result.failure(
            lastException ?: QodanaErrorException(
                QodanaError.Network(url, "Request failed after $maxRetries retries"),
            ),
        )
    }
}

/**
 * Wrapper to carry [QodanaError] inside Kotlin [Result].
 */
class QodanaErrorException(
    val error: QodanaError,
) : Exception(error.message)

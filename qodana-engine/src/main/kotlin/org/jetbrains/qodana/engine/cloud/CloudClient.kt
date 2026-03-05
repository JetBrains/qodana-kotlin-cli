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
    }

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
    )

    suspend fun fetchEndpoints(): Result<CloudEndpoints> {
        val url = "${endpoint.trimEnd('/')}/api/config.json"
        return executeWithRetries(url) { requestUrl ->
            val response = http.get(requestUrl)
            if (!response.isSuccess) {
                return@executeWithRetries Result.failure(
                    QodanaErrorException(
                        QodanaError.Network(requestUrl, "HTTP ${response.statusCode}: ${response.body}")
                    )
                )
            }
            Result.success(mapper.readValue<CloudEndpoints>(response.body))
        }
    }

    suspend fun <T> getAuthenticated(url: String, responseType: Class<T>): Result<T> {
        return executeWithRetries(url) { requestUrl ->
            val response = http.get(requestUrl, authHeaders())
            if (!response.isSuccess) {
                return@executeWithRetries Result.failure(
                    QodanaErrorException(
                        QodanaError.Network(requestUrl, "HTTP ${response.statusCode}: ${response.body}")
                    )
                )
            }
            Result.success(mapper.readValue(response.body, responseType))
        }
    }

    suspend inline fun <reified T> getAuthenticated(url: String): Result<T> {
        return getAuthenticated(url, T::class.java)
    }

    private suspend fun <T> executeWithRetries(
        url: String,
        action: suspend (String) -> Result<T>,
    ): Result<T> {
        var lastException: Throwable? = null
        repeat(maxRetries) { attempt ->
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
                QodanaError.Network(url, "Request failed after $maxRetries retries")
            )
        )
    }
}

/**
 * Wrapper to carry [QodanaError] inside Kotlin [Result].
 */
class QodanaErrorException(val error: QodanaError) : Exception(error.message)

package org.jetbrains.qodana.infra.http

import org.jetbrains.qodana.core.port.HttpResponse
import org.jetbrains.qodana.core.port.HttpTransport
import org.jetbrains.qodana.core.port.MultipartPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.outputStream

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(60))
        .writeTimeout(Duration.ofSeconds(60))
        .build(),
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        execute(Request.Builder().url(url).headers(headers.toHeaders()).get().build())

    override suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse {
        val requestBody = body.toRequestBody(contentType.toMediaType())
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .post(requestBody)
            .build()
        return execute(request)
    }

    override suspend fun download(url: String, target: Path, headers: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).headers(headers.toHeaders()).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code, "Download failed: ${response.message}")
                }
                response.body?.byteStream()?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    override suspend fun uploadMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String>,
    ): HttpResponse {
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                parts.forEach { part ->
                    when (part) {
                        is MultipartPart.Field -> addFormDataPart(part.name, part.value)
                        is MultipartPart.File -> addFormDataPart(
                            part.name,
                            part.filename,
                            part.path.toFile().asRequestBody(part.contentType.toMediaType()),
                        )
                    }
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .post(multipartBody)
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): HttpResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            HttpResponse(
                statusCode = response.code,
                body = response.body?.string() ?: "",
                headers = response.headers.toMultimap(),
            )
        }
    }
}

class HttpException(val statusCode: Int, message: String) : RuntimeException(message)

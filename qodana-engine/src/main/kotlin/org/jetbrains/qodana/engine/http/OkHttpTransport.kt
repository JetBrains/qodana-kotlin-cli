package org.jetbrains.qodana.engine.http

import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.outputStream

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(60))
        .writeTimeout(Duration.ofSeconds(60))
        .build(),
    private val getEnv: (String) -> String? = System::getenv,
) : HttpTransport {
    companion object {
        private const val BITBUCKET_API_HOST = "api.bitbucket.org"
        private const val BITBUCKET_PIPELINE_UUID = "BITBUCKET_PIPELINE_UUID"
        private const val BITBUCKET_PIPE_STORAGE_DIR = "BITBUCKET_PIPE_STORAGE_DIR"
        private const val BITBUCKET_PIPE_SHARED_STORAGE_DIR = "BITBUCKET_PIPE_SHARED_STORAGE_DIR"
        private const val BITBUCKET_PIPELINE_PROXY_HOST = "localhost"
        private const val BITBUCKET_PIPE_PROXY_HOST = "host.docker.internal"
        private const val BITBUCKET_PROXY_PORT = 29418
    }

    private val bitbucketProxyClient: OkHttpClient by lazy {
        val proxyHost = if (isBitBucketPipe()) BITBUCKET_PIPE_PROXY_HOST else BITBUCKET_PIPELINE_PROXY_HOST
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, BITBUCKET_PROXY_PORT))
        client.newBuilder().proxy(proxy).build()
    }

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
            selectClient(request.url).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code, "Download failed")
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
        selectClient(request.url).newCall(request).execute().use { response ->
            HttpResponse(
                statusCode = response.code,
                body = response.body?.string() ?: "",
                headers = response.headers.toMultimap(),
            )
        }
    }

    private fun selectClient(url: HttpUrl): OkHttpClient {
        val bitBucketPipelines = !getEnv(BITBUCKET_PIPELINE_UUID).isNullOrBlank()
        val isBitbucketApi = url.host.equals(BITBUCKET_API_HOST, ignoreCase = true)
        return if (bitBucketPipelines && isBitbucketApi) bitbucketProxyClient else client
    }

    private fun isBitBucketPipe(): Boolean {
        return !getEnv(BITBUCKET_PIPE_STORAGE_DIR).isNullOrBlank() ||
            !getEnv(BITBUCKET_PIPE_SHARED_STORAGE_DIR).isNullOrBlank()
    }
}

class HttpException(val statusCode: Int, message: String) : RuntimeException("response code '$statusCode', message '$message'")

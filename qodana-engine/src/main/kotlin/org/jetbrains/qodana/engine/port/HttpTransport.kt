package org.jetbrains.qodana.engine.port

import java.nio.file.Path

interface HttpTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    suspend fun download(
        url: String,
        target: Path,
        headers: Map<String, String> = emptyMap(),
    )

    suspend fun uploadMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse
}

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}

sealed interface MultipartPart {
    data class Field(
        val name: String,
        val value: String,
    ) : MultipartPart

    data class File(
        val name: String,
        val filename: String,
        val contentType: String,
        val path: Path,
    ) : MultipartPart
}

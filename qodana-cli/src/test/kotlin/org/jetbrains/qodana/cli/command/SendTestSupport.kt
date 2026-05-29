package org.jetbrains.qodana.cli.command

import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.model.PublishResult
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.jetbrains.qodana.engine.port.ReportPublisher
import org.jetbrains.qodana.engine.port.TokenStore
import java.nio.file.Path

internal class SendRecordingReportPublisher : ReportPublisher {
    var calls: Int = 0
    var lastToken: String? = null

    override suspend fun publish(
        analysisId: String,
        reportPath: Path,
        token: String,
        endpoint: String,
    ): PublishResult {
        calls++
        lastToken = token
        return PublishResult(
            url = "https://qodana.cloud/report/1",
            reportId = "1",
            success = true,
        )
    }
}

internal class SendFixedTokenStore(
    private val token: String?,
) : TokenStore {
    override fun load(key: String): String? = token

    override fun save(
        key: String,
        value: String,
    ) = Unit

    override fun delete(key: String) = Unit
}

internal class SendFakeHttpTransport(
    private val responses: Map<String, HttpResponse>,
) : HttpTransport {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse {
        requests += url to headers
        return responses[url] ?: HttpResponse(404, "Not Found")
    }

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
    ) = Unit

    override suspend fun uploadMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String>,
    ): HttpResponse = HttpResponse(200, "")
}

internal class SendTestTerminal(
    private val prompts: ArrayDeque<String> = ArrayDeque(),
    override val isInteractive: Boolean,
) : Terminal {
    val messages = mutableListOf<String>()
    override var isCi: Boolean = false

    override fun print(message: String) {
        messages += message
    }

    override fun println(message: String) {
        messages += message
    }

    override fun error(message: String) {
        messages += "ERROR: $message"
    }

    override fun info(message: String) {
        messages += "INFO: $message"
    }

    override fun warn(message: String) {
        messages += "WARN: $message"
    }

    override fun debug(message: String) {
        messages += "DEBUG: $message"
    }

    override fun <T> spinner(
        message: String,
        action: () -> T,
    ): T = action()

    override fun prompt(
        message: String,
        default: String?,
    ): String = if (prompts.isNotEmpty()) prompts.removeFirst() else (default ?: "")

    override fun select(
        message: String,
        choices: List<String>,
    ): String = choices.first()

    override fun setRedactedTokens(tokens: Set<String>) = Unit
}

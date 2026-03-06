package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.model.PublishResult
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.jetbrains.qodana.engine.port.ReportPublisher
import org.jetbrains.qodana.engine.port.TokenStore
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendCommandTest {

    @Test
    fun `send prefers environment token over stored token`(@TempDir dir: Path) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        val resultsDir = dir.resolve("results").also { Files.createDirectories(it) }
        val terminal = SendTestTerminal(isInteractive = false)
        val env = mapOf(
            "QODANA_TOKEN" to "env-token",
            "QODANA_ENDPOINT" to "https://qodana.cloud",
        )
        val http = SendFakeHttpTransport(
            mapOf(
                "https://qodana.cloud/api/config.json" to HttpResponse(
                    200,
                    """{"LintersApiUrl":"https://linters.api","CloudApiUrl":"https://cloud.api"}"""
                ),
                "https://cloud.api/projects" to HttpResponse(200, """{"name":"sample"}""")
            )
        )
        val publisher = SendRecordingReportPublisher()

        SendCommand(
            reportPublisher = ReportPublishUseCase(publisher),
            terminal = terminal,
            getEnv = { key -> env[key] },
            tokenStore = SendFixedTokenStore("stored-token"),
            httpTransport = http,
            isContainer = { false },
        ).parse(
            listOf("-i", projectDir.toString(), "-o", resultsDir.toString())
        )

        assertEquals("env-token", publisher.lastToken)
        val projectRequest = http.requests.first { it.first == "https://cloud.api/projects" }
        assertEquals("Bearer env-token", projectRequest.second["Authorization"])
        assertFalse(terminal.messages.any { it.contains("system keyring") })
    }

    @Test
    fun `send falls back to stored token and prints warning`(@TempDir dir: Path) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        val resultsDir = dir.resolve("results").also { Files.createDirectories(it) }
        val terminal = SendTestTerminal(isInteractive = false)
        val http = SendFakeHttpTransport(
            mapOf(
                "https://qodana.cloud/api/config.json" to HttpResponse(
                    200,
                    """{"LintersApiUrl":"https://linters.api","CloudApiUrl":"https://cloud.api"}"""
                ),
                "https://cloud.api/projects" to HttpResponse(200, """{"name":"sample"}""")
            )
        )
        val publisher = SendRecordingReportPublisher()

        SendCommand(
            reportPublisher = ReportPublishUseCase(publisher),
            terminal = terminal,
            getEnv = { _ -> null },
            tokenStore = SendFixedTokenStore("stored-token"),
            httpTransport = http,
            isContainer = { false },
        ).parse(
            listOf("-i", projectDir.toString(), "-o", resultsDir.toString())
        )

        assertEquals("stored-token", publisher.lastToken)
        assertTrue(terminal.messages.any { it.contains("Got QODANA_TOKEN from the system keyring") })
    }

    @Test
    fun `send exits with invalid token message when cloud validation fails`(@TempDir dir: Path) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        val resultsDir = dir.resolve("results").also { Files.createDirectories(it) }
        val terminal = SendTestTerminal(isInteractive = false)
        val env = mapOf(
            "QODANA_TOKEN" to "bad-token",
            "QODANA_ENDPOINT" to "https://qodana.cloud",
        )
        val http = SendFakeHttpTransport(
            mapOf(
                "https://qodana.cloud/api/config.json" to HttpResponse(
                    200,
                    """{"LintersApiUrl":"https://linters.api","CloudApiUrl":"https://cloud.api"}"""
                ),
                "https://cloud.api/projects" to HttpResponse(401, "unauthorized")
            )
        )
        val publisher = SendRecordingReportPublisher()

        val exception = assertFailsWith<ProgramResult> {
            SendCommand(
                reportPublisher = ReportPublishUseCase(publisher),
                terminal = terminal,
                getEnv = { key -> env[key] },
                tokenStore = SendFixedTokenStore(null),
                httpTransport = http,
                isContainer = { false },
            ).parse(
                listOf("-i", projectDir.toString(), "-o", resultsDir.toString())
            )
        }

        assertEquals(1, exception.statusCode)
        assertEquals(0, publisher.calls)
        assertTrue(terminal.messages.any { it.contains("QODANA_TOKEN is invalid, please provide a valid token") })
    }
}

private class SendRecordingReportPublisher : ReportPublisher {
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

private class SendFixedTokenStore(
    private val token: String?,
) : TokenStore {
    override fun load(key: String): String? = token
    override fun save(key: String, value: String) {}
    override fun delete(key: String) {}
}

private class SendFakeHttpTransport(
    private val responses: Map<String, HttpResponse>,
) : HttpTransport {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += url to headers
        return responses[url] ?: HttpResponse(404, "Not Found")
    }

    override suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse {
        return HttpResponse(200, "")
    }

    override suspend fun download(url: String, target: Path, headers: Map<String, String>) {}

    override suspend fun uploadMultipart(
        url: String,
        parts: List<MultipartPart>,
        headers: Map<String, String>,
    ): HttpResponse {
        return HttpResponse(200, "")
    }
}

private class SendTestTerminal(
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

    override fun <T> spinner(message: String, action: () -> T): T = action()

    override fun prompt(message: String, default: String?): String {
        return if (prompts.isNotEmpty()) prompts.removeFirst() else (default ?: "")
    }

    override fun select(message: String, choices: List<String>): String = choices.first()

    override fun setRedactedTokens(tokens: Set<String>) {}
}

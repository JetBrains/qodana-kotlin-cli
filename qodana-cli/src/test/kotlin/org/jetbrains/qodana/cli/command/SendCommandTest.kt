package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.env.RuntimeEnvironment
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
    fun `send prefers environment token over stored token`(
        @TempDir dir: Path,
    ) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        val resultsDir = dir.resolve("results").also { Files.createDirectories(it) }
        val terminal = SendTestTerminal(isInteractive = false)
        val env =
            mapOf(
                "QODANA_TOKEN" to "env-token",
                "QODANA_ENDPOINT" to "https://qodana.cloud",
            )
        val http =
            SendFakeHttpTransport(
                mapOf(
                    "https://qodana.cloud/api/versions" to
                        HttpResponse(
                            200,
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
                            """.trimIndent(),
                        ),
                    "https://cloud.api/projects" to HttpResponse(200, """{"name":"sample"}"""),
                ),
            )
        val publisher = SendRecordingReportPublisher()

        SendCommand(
            reportPublisher = ReportPublishUseCase(publisher),
            terminal = terminal,
            getEnv = { key -> env[key] },
            tokenStore = SendFixedTokenStore("stored-token"),
            httpTransport = http,
            runtimeEnvironmentDetector = { RuntimeEnvironment.HOST },
        ).parse(
            listOf("-i", projectDir.toString(), "-o", resultsDir.toString()),
        )

        assertEquals("env-token", publisher.lastToken)
        val projectRequest = http.requests.first { it.first == "https://cloud.api/projects" }
        assertEquals("Bearer env-token", projectRequest.second["Authorization"])
        assertFalse(terminal.messages.any { it.contains("system keyring") })
    }

    @Test
    fun `send falls back to stored token and prints warning`(
        @TempDir dir: Path,
    ) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        val resultsDir = dir.resolve("results").also { Files.createDirectories(it) }
        val terminal = SendTestTerminal(isInteractive = false)
        val http =
            SendFakeHttpTransport(
                mapOf(
                    "https://qodana.cloud/api/versions" to
                        HttpResponse(
                            200,
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
                            """.trimIndent(),
                        ),
                    "https://cloud.api/projects" to HttpResponse(200, """{"name":"sample"}"""),
                ),
            )
        val publisher = SendRecordingReportPublisher()

        SendCommand(
            reportPublisher = ReportPublishUseCase(publisher),
            terminal = terminal,
            getEnv = { _ -> null },
            tokenStore = SendFixedTokenStore("stored-token"),
            httpTransport = http,
            runtimeEnvironmentDetector = { RuntimeEnvironment.HOST },
        ).parse(
            listOf("-i", projectDir.toString(), "-o", resultsDir.toString()),
        )

        assertEquals("stored-token", publisher.lastToken)
        assertTrue(terminal.messages.any { it.contains("Got QODANA_TOKEN from the system keyring") })
    }

    @Test
    fun `send exits with invalid token message when cloud validation fails`(
        @TempDir dir: Path,
    ) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        val resultsDir = dir.resolve("results").also { Files.createDirectories(it) }
        val terminal = SendTestTerminal(isInteractive = false)
        val env =
            mapOf(
                "QODANA_TOKEN" to "bad-token",
                "QODANA_ENDPOINT" to "https://qodana.cloud",
            )
        val http =
            SendFakeHttpTransport(
                mapOf(
                    "https://qodana.cloud/api/versions" to
                        HttpResponse(
                            200,
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
                            """.trimIndent(),
                        ),
                    "https://cloud.api/projects" to HttpResponse(401, "unauthorized"),
                ),
            )
        val publisher = SendRecordingReportPublisher()

        val exception =
            assertFailsWith<ProgramResult> {
                SendCommand(
                    reportPublisher = ReportPublishUseCase(publisher),
                    terminal = terminal,
                    getEnv = { key -> env[key] },
                    tokenStore = SendFixedTokenStore(null),
                    httpTransport = http,
                    runtimeEnvironmentDetector = { RuntimeEnvironment.HOST },
                ).parse(
                    listOf("-i", projectDir.toString(), "-o", resultsDir.toString()),
                )
            }

        assertEquals(1, exception.statusCode)
        assertEquals(0, publisher.calls)
        assertTrue(terminal.messages.any { it.contains("QODANA_TOKEN is invalid, please provide a valid token") })
    }
}

// Helpers moved to SendTestSupport.kt (package-internal, shared with NativeSmokeTest)

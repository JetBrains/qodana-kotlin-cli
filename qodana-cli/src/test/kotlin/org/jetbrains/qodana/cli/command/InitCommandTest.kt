package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.jetbrains.qodana.engine.port.TokenStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitCommandTest {

    @Test
    fun `init exits for unsupported project`(@TempDir dir: Path) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        val terminal = InitTestTerminal(isInteractive = false)

        val exception = assertFailsWith<ProgramResult> {
            InitCommand(
                terminal = terminal,
                getEnv = { _ -> null },
                tokenStore = InitFixedTokenStore(null),
                httpTransport = InitFakeHttpTransport(emptyMap()),
                isContainer = { false },
            ).parse(listOf("-i", projectDir.toString()))
        }

        assertEquals(1, exception.statusCode)
        assertFalse(Files.exists(projectDir.resolve("qodana.yaml")))
        assertTrue(terminal.messages.any { it.contains("Could not configure project as it is not supported by Qodana") })
        assertTrue(terminal.messages.any { it.contains("supported-technologies") })
    }

    @Test
    fun `init validates token for paid linter and fails on invalid token`(@TempDir dir: Path) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        Files.writeString(projectDir.resolve("main.py"), "print('ok')")

        val terminal = InitTestTerminal(isInteractive = false)
        val env = mapOf(
            "QODANA_TOKEN" to "bad-token",
            "QODANA_ENDPOINT" to "https://qodana.cloud",
        )
        val http = InitFakeHttpTransport(
            mapOf(
                "https://qodana.cloud/api/config.json" to HttpResponse(
                    200,
                    """{"LintersApiUrl":"https://linters.api","CloudApiUrl":"https://cloud.api"}"""
                ),
                "https://cloud.api/projects" to HttpResponse(401, "unauthorized")
            )
        )

        val exception = assertFailsWith<ProgramResult> {
            InitCommand(
                terminal = terminal,
                getEnv = { key -> env[key] },
                tokenStore = InitFixedTokenStore(null),
                httpTransport = http,
                isContainer = { false },
            ).parse(listOf("-i", projectDir.toString()))
        }

        assertEquals(1, exception.statusCode)
        assertTrue(terminal.messages.any { it.contains("QODANA_TOKEN is invalid, please provide a valid token") })
        val yamlFile = projectDir.resolve("qodana.yaml")
        assertTrue(Files.exists(yamlFile))
        assertTrue(Files.readString(yamlFile).contains("linter: qodana-python"))
    }

    @Test
    fun `init interactive dotnet setup updates yaml with selected solution`(@TempDir dir: Path) {
        val projectDir = dir.resolve("project").also { Files.createDirectories(it) }
        Files.createDirectories(projectDir.resolve("src"))
        Files.writeString(projectDir.resolve("src/app.sln"), "")
        Files.writeString(projectDir.resolve("src/lib.csproj"), "")
        Files.writeString(
            projectDir.resolve("qodana.yaml"),
            """
            version: "1.0"
            linter: qodana-dotnet
            """.trimIndent(),
        )

        val terminal = InitTestTerminal(
            isInteractive = true,
            selectAnswers = ArrayDeque(listOf("src/app.sln")),
        )

        InitCommand(
            terminal = terminal,
            getEnv = { _ -> null },
            tokenStore = InitFixedTokenStore(null),
            httpTransport = InitFakeHttpTransport(emptyMap()),
            isContainer = { false },
        ).parse(listOf("-i", projectDir.toString()))

        val updatedYaml = Files.readString(projectDir.resolve("qodana.yaml"))
        assertTrue(updatedYaml.contains("dotnet:"))
        assertTrue(updatedYaml.contains("solution: app.sln"))
        assertTrue(terminal.messages.any { it.contains("The .NET configuration was successfully set") })
    }
}

private class InitFixedTokenStore(
    private val token: String?,
) : TokenStore {
    override fun load(key: String): String? = token
    override fun save(key: String, value: String) {}
    override fun delete(key: String) {}
}

private class InitFakeHttpTransport(
    private val responses: Map<String, HttpResponse>,
) : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
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

private class InitTestTerminal(
    private val prompts: ArrayDeque<String> = ArrayDeque(),
    private val selectAnswers: ArrayDeque<String> = ArrayDeque(),
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

    override fun select(message: String, choices: List<String>): String {
        return if (selectAnswers.isNotEmpty()) selectAnswers.removeFirst() else choices.first()
    }

    override fun setRedactedTokens(tokens: Set<String>) {}
}

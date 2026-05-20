package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.cloud.CloudClient
import org.jetbrains.qodana.engine.cloud.parseProjectName
import org.jetbrains.qodana.engine.cloud.parseRawUrl
import org.jetbrains.qodana.engine.env.RuntimeEnvironment
import org.jetbrains.qodana.engine.env.RuntimeEnvironmentDetector
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.model.AuthContext
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.TokenStore
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.token.FileTokenStore
import java.nio.file.Path
import java.util.UUID

class SendCommand(
    private val reportPublisher: () -> ReportPublishUseCase,
    private val terminal: Terminal,
    private val getEnv: (String) -> String? = System::getenv,
    private val tokenStore: () -> TokenStore = { FileTokenStore() },
    private val httpTransport: () -> HttpTransport = { OkHttpTransport() },
    private val runtimeEnvironmentDetector: () -> RuntimeEnvironment = { RuntimeEnvironmentDetector.detect() },
) : CliktCommand("send") {
    // Secondary constructor for tests passing concrete instances. The heavy deps
    // are non-default so this overload is unambiguous against the primary's lazy
    // form — calls that don't pass tokenStore/httpTransport hit the primary and
    // its lazy { ... } defaults.
    constructor(
        reportPublisher: ReportPublishUseCase,
        terminal: Terminal,
        getEnv: (String) -> String?,
        tokenStore: TokenStore,
        httpTransport: HttpTransport,
        runtimeEnvironmentDetector: () -> RuntimeEnvironment = { RuntimeEnvironmentDetector.detect() },
    ) : this(
        reportPublisher = { reportPublisher },
        terminal = terminal,
        getEnv = getEnv,
        tokenStore = { tokenStore },
        httpTransport = { httpTransport },
        runtimeEnvironmentDetector = runtimeEnvironmentDetector,
    )

    override fun help(context: Context) = "Send Qodana report to Qodana Cloud"

    private val linter by option("-l", "--linter", help = "Override linter to use")
    private val projectDir by option("-i", "--project-dir", help = "Root directory of the inspected project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val resultsDir by option("-o", "--results-dir", help = "Directory with analysis results")
        .path(mustExist = true)
    private val reportDir by option("-r", "--report-dir", help = "Override report directory")
        .path()
    private val configName by option("--config", help = "Custom configuration file instead of qodana.yaml")
    private val analysisId by option("-a", "--analysis-id", help = "Analysis ID")
        .default(UUID.randomUUID().toString())

    override fun run() =
        runBlocking {
            val token = loadCloudToken()
            if (token == null) {
                terminal.error("QODANA_TOKEN environment variable is not set")
                throw ProgramResult(1)
            }
            validateTokenOrExit(token)

            val absProjectDir = projectDir.toAbsolutePath().normalize()
            val yaml = CliPathResolver.loadYaml(absProjectDir, configName)
            val resolvedLinter = CliPathResolver.resolveLinterName(linter, yaml, absProjectDir)
            val resolvedPaths =
                CliPathResolver.resolvePaths(
                    projectDir = absProjectDir,
                    linterName = resolvedLinter,
                    resultsDir = resultsDir,
                    cacheDir = null,
                    reportDir = reportDir,
                    runtimeEnvironment = runtimeEnvironmentDetector(),
                )

            val auth =
                AuthContext(
                    token = token,
                    endpoint = cloudRootEndpoint(),
                )

            val result =
                reportPublisher().publish(
                    analysisId = analysisId,
                    reportPath = resolvedPaths.resultsDir,
                    auth = auth,
                )

            result
                .onSuccess {
                    if (it.success) {
                        terminal.println("Report published: ${it.url}")
                    } else {
                        terminal.error("Failed to publish report")
                        throw ProgramResult(1)
                    }
                }.onFailure {
                    terminal.error("Failed to publish report: ${it.message}")
                    throw ProgramResult(1)
                }
            Unit
        }

    private fun loadCloudToken(): String? {
        val envToken = getEnv(QodanaEnv.TOKEN)?.trim().orEmpty()
        if (envToken.isNotEmpty()) {
            return envToken
        }

        val storedToken =
            runCatching { tokenStore().load(QodanaEnv.TOKEN)?.trim() }
                .getOrNull()
                .orEmpty()
        if (storedToken.isNotEmpty()) {
            terminal.warn(TOKEN_STORE_WARNING)
            return storedToken
        }

        if (!terminal.isInteractive) {
            return null
        }
        val token =
            terminal
                .prompt(
                    "Enter the token (will be used for ${projectDir.toAbsolutePath().normalize()}; enter 'q' to exit)",
                ).trim()
        if (token.equals("q", ignoreCase = true) || token.isEmpty()) {
            return null
        }

        runCatching { tokenStore().save(QodanaEnv.TOKEN, token) }
            .onFailure { terminal.warn("Failed to save credentials: ${it.message}") }
        return token
    }

    private suspend fun validateTokenOrExit(token: String) {
        val http = httpTransport()
        val cloudRoot = cloudRootEndpoint()
        val cloudClient =
            CloudClient(
                http = http,
                endpoint = cloudRoot,
                token = token,
            )
        val endpoints = cloudClient.fetchEndpoints().getOrNull()
        if (endpoints == null) {
            terminal.error(INVALID_TOKEN_MESSAGE)
            throw ProgramResult(1)
        }

        val response =
            runCatching {
                http.get(
                    "${endpoints.cloudApiUrl.trimEnd('/')}/projects",
                    headers = mapOf("Authorization" to "Bearer $token"),
                )
            }.getOrNull()

        if (response == null || !response.isSuccess) {
            terminal.error(INVALID_TOKEN_MESSAGE)
            throw ProgramResult(1)
        }

        val projectName = parseProjectName(response.body).getOrNull()
        if (projectName == null) {
            terminal.error(INVALID_TOKEN_MESSAGE)
            throw ProgramResult(1)
        }

        if (runtimeEnvironmentDetector() == RuntimeEnvironment.HOST) {
            terminal.println("Linked $cloudRoot project: $projectName")
        }
    }

    private fun cloudRootEndpoint(): String {
        val endpoint = getEnv(QodanaEnv.ENDPOINT).orEmpty().ifBlank { QodanaEnv.DEFAULT_ENDPOINT }
        return parseRawUrl(endpoint).getOrElse { endpoint }
    }

    private companion object {
        const val INVALID_TOKEN_MESSAGE = "QODANA_TOKEN is invalid, please provide a valid token"
        const val TOKEN_STORE_WARNING =
            "Got QODANA_TOKEN from the system keyring, declare QODANA_TOKEN env variable or run qodana init -f to override it"
    }
}

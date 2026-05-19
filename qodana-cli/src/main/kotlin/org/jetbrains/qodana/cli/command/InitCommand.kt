package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.isDotNet
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.product.Linter
import org.jetbrains.qodana.engine.cloud.CloudClient
import org.jetbrains.qodana.engine.cloud.LicenseToken
import org.jetbrains.qodana.engine.cloud.parseProjectName
import org.jetbrains.qodana.engine.cloud.parseRawUrl
import org.jetbrains.qodana.engine.env.RuntimeEnvironment
import org.jetbrains.qodana.engine.env.RuntimeEnvironmentDetector
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.TokenStore
import org.jetbrains.qodana.engine.scan.ProjectDetector
import org.jetbrains.qodana.engine.token.FileTokenStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

class InitCommand(
    private val terminal: Terminal,
    private val getEnv: (String) -> String? = System::getenv,
    private val tokenStore: TokenStore = FileTokenStore(),
    private val httpTransport: HttpTransport = OkHttpTransport(),
    private val runtimeEnvironmentDetector: () -> RuntimeEnvironment = { RuntimeEnvironmentDetector.detect() },
) : CliktCommand("init") {
    override fun help(context: Context) = "Configure a Qodana project by creating a qodana.yaml file"

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val force by option("-f", "--force", help = "Force initialization (overwrite existing valid qodana.yaml)")
        .flag()
    private val configName by option("--config", help = "Custom configuration file instead of qodana.yaml")

    override fun run() =
        runBlocking {
            val absProjectDir = projectDir.toAbsolutePath().normalize()
            val yamlFile = resolveYamlFile(absProjectDir, configName)
            val isExisting = Files.exists(yamlFile)
            val content = if (isExisting) Files.readString(yamlFile) else ""
            val analyzerAlreadyConfigured = isExisting && !force && isAnalyzerConfigured(content)

            if (analyzerAlreadyConfigured) {
                terminal.println(
                    "The product to use was already configured before. Run the command with -f flag to re-init the project",
                )
            } else {
                val detectedLinter = ProjectDetector.detectLinter(absProjectDir)
                if (detectedLinter == null) {
                    terminal.error("Could not configure project as it is not supported by Qodana")
                    terminal.warn("See https://www.jetbrains.com/help/qodana/supported-technologies.html for more details")
                    throw ProgramResult(1)
                }

                if (isExisting) {
                    updateExistingYaml(yamlFile, detectedLinter.name)
                } else {
                    createNewYaml(yamlFile, detectedLinter.name)
                }
                checkCloudTokenIfRequired(detectedLinter)
            }

            configureDotNetIfNeeded(absProjectDir, yamlFile)
            terminal.println(yamlFile.toString())
        }

    private fun resolveYamlFile(
        dir: Path,
        customConfigName: String?,
    ): Path {
        if (!customConfigName.isNullOrBlank()) {
            return dir.resolve(customConfigName)
        }
        val yamlNames = listOf("qodana.yaml", "qodana.yml")
        return yamlNames.map { dir.resolve(it) }.firstOrNull { Files.exists(it) }
            ?: dir.resolve("qodana.yaml")
    }

    private fun isAnalyzerConfigured(content: String): Boolean =
        content.lines().any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("linter:") || trimmed.startsWith("ide:") || trimmed.startsWith("image:")
        }

    private fun updateExistingYaml(
        yamlFile: Path,
        linterName: String,
    ) {
        val content = Files.readString(yamlFile)
        val lines = content.lines().toMutableList()

        val linterIdx = lines.indexOfFirst { it.trimStart().startsWith("linter:") }
        if (linterIdx >= 0) {
            lines[linterIdx] = "linter: $linterName"
        } else {
            lines.add("linter: $linterName")
        }
        if (lines.none { it.trimStart().startsWith("version:") }) {
            lines.add(0, "version: \"1.0\"")
        }

        Files.writeString(yamlFile, lines.joinToString("\n"))
        terminal.println("Updated $yamlFile with linter: $linterName")
    }

    private fun createNewYaml(
        yamlFile: Path,
        linterName: String,
    ) {
        val yaml =
            """
            |version: "1.0"
            |linter: $linterName
            |profile:
            |  name: qodana.recommended
            """.trimMargin()

        Files.writeString(yamlFile, yaml)
        terminal.println("Created $yamlFile with linter: $linterName")
    }

    private fun configureDotNetIfNeeded(
        projectDir: Path,
        yamlFile: Path,
    ) {
        if (!terminal.isInteractive) return

        val yaml = CliPathResolver.loadYaml(projectDir, configName) ?: return
        if (!yaml.isDotNet()) return
        if (!force && yaml.dotnet?.isEmpty() == false) return

        val options = findDotNetOptions(projectDir)
        if (options.size <= 1) return

        terminal.warn("Detected multiple .NET solution/project files, select the preferred one")
        val optionLabels = options.map { projectDir.relativize(it).toString() }
        val selected = terminal.select("Select solution/project", optionLabels)
        val selectedPath = options.getOrNull(optionLabels.indexOf(selected)) ?: return

        if (writeDotNetConfig(yamlFile, selectedPath.fileName.toString())) {
            terminal.println("The .NET configuration was successfully set")
        }
    }

    private fun findDotNetOptions(projectDir: Path): List<Path> {
        if (!Files.isDirectory(projectDir)) return emptyList()
        val files = mutableListOf<Path>()
        val supportedExtensions = setOf("sln", "csproj", "vbproj", "fsproj")
        Files.walk(projectDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { path ->
                    if (path.extension.lowercase() in supportedExtensions) {
                        files.add(path.toAbsolutePath().normalize())
                    }
                }
        }
        return files.sorted()
    }

    private fun writeDotNetConfig(
        yamlFile: Path,
        selectedFileName: String,
    ): Boolean {
        val lines = Files.readString(yamlFile).lines().toMutableList()
        val block =
            if (selectedFileName.endsWith(".sln", ignoreCase = true)) {
                listOf("dotnet:", "  solution: $selectedFileName")
            } else {
                listOf("dotnet:", "  project: $selectedFileName")
            }

        val existingIdx = lines.indexOfFirst { it.trimStart().startsWith("dotnet:") }
        if (existingIdx >= 0) {
            var end = existingIdx + 1
            while (end < lines.size && (lines[end].startsWith("  ") || lines[end].isBlank())) {
                end++
            }
            lines.subList(existingIdx, end).clear()
            lines.addAll(existingIdx, block)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) {
                lines.add("")
            }
            lines.addAll(block)
        }

        Files.writeString(yamlFile, lines.joinToString("\n"))
        return true
    }

    private suspend fun checkCloudTokenIfRequired(linter: Linter) {
        val tokenRequired =
            LicenseToken.isCloudTokenRequired(
                qodanaToken = getEnv(QodanaEnv.TOKEN),
                licenseOnlyToken = getEnv(QodanaEnv.LICENSE_ONLY_TOKEN),
                licenseEnv = getEnv(QodanaEnv.LICENSE),
                isPaid = linter.isPaid,
                isEap = linter.eapOnly,
            )
        if (!tokenRequired) return

        val token = loadCloudToken() ?: return
        validateTokenOrExit(token)
    }

    private fun loadCloudToken(): String? {
        val envToken = getEnv(QodanaEnv.TOKEN)?.trim().orEmpty()
        if (envToken.isNotEmpty()) {
            return envToken
        }

        val storedToken =
            runCatching { tokenStore.load(QodanaEnv.TOKEN)?.trim() }
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

        runCatching { tokenStore.save(QodanaEnv.TOKEN, token) }
            .onFailure { terminal.warn("Failed to save credentials: ${it.message}") }
        return token
    }

    private suspend fun validateTokenOrExit(token: String) {
        val cloudRoot = cloudRootEndpoint()
        val cloudClient =
            CloudClient(
                http = httpTransport,
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
                httpTransport.get(
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

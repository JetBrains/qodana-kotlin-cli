package org.jetbrains.qodana.cli.command

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.jetbrains.qodana.core.model.QodanaYaml
import org.jetbrains.qodana.engine.scan.ProjectDetector
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class CommandPaths(
    val resultsDir: Path,
    val cacheDir: Path,
    val reportDir: Path,
)

internal object CliPathResolver {
    fun loadYaml(projectDir: Path, customConfigName: String?): QodanaYaml? {
        val yamlPath = when {
            !customConfigName.isNullOrBlank() -> projectDir.resolve(customConfigName)
            Files.exists(projectDir.resolve("qodana.yaml")) -> projectDir.resolve("qodana.yaml")
            Files.exists(projectDir.resolve("qodana.yml")) -> projectDir.resolve("qodana.yml")
            else -> null
        } ?: return null

        return runCatching {
            YAMLMapper.builder()
                .addModule(kotlinModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build()
                .readValue(yamlPath.toFile(), QodanaYaml::class.java)
        }.getOrNull()
    }

    fun resolveLinterName(cliLinter: String?, yaml: QodanaYaml?, projectDir: Path): String {
        return cliLinter
            ?: yaml?.linter
            ?: yaml?.image
            ?: ProjectDetector.detectLinter(projectDir)?.name
            ?: "qodana-jvm"
    }

    fun resolvePaths(
        projectDir: Path,
        linterName: String,
        resultsDir: Path?,
        cacheDir: Path?,
        reportDir: Path?,
        isContainer: Boolean,
    ): CommandPaths {
        val defaults = if (isContainer) {
            Triple(Path.of("/data/results"), Path.of("/data/cache"), Path.of("/data/results/report"))
        } else {
            val linterDir = qodanaSystemDir().resolve(computeScanId(linterName, projectDir))
            val defaultResults = linterDir.resolve("results")
            val defaultCache = linterDir.resolve("cache")
            val defaultReport = defaultResults.resolve("report")
            Triple(defaultResults, defaultCache, defaultReport)
        }

        val resolvedResults = resultsDir?.toAbsolutePath()?.normalize() ?: defaults.first
        val resolvedCache = cacheDir?.toAbsolutePath()?.normalize() ?: defaults.second
        val resolvedReport = reportDir?.toAbsolutePath()?.normalize() ?: defaults.third

        return CommandPaths(
            resultsDir = resolvedResults,
            cacheDir = resolvedCache,
            reportDir = resolvedReport,
        )
    }

    private fun qodanaSystemDir(): Path {
        val home = System.getProperty("user.home") ?: "."
        val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
        val userCache = if (isMac) {
            Path.of(home, "Library", "Caches")
        } else {
            Path.of(home, ".cache")
        }
        return userCache.resolve("JetBrains").resolve("Qodana")
    }

    private fun computeScanId(linterName: String, projectDir: Path): String {
        val linterHash = sha256(linterName).take(8)
        val projectHash = sha256(projectDir.toString()).take(8)
        return "$linterHash-$projectHash"
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

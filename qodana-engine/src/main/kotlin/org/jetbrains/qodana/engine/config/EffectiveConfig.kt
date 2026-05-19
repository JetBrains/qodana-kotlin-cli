package org.jetbrains.qodana.engine.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import java.nio.file.Files
import java.nio.file.Path

object EffectiveConfig {
    private val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(
        projectDir: Path,
        customConfigName: String? = null,
    ): QodanaYaml? {
        val yamlFile = resolveYamlPath(projectDir, customConfigName) ?: return null
        return parse(Files.readString(yamlFile))
    }

    fun parse(yamlContent: String): QodanaYaml = yamlMapper.readValue(yamlContent, QodanaYaml::class.java)

    fun resolveYamlPath(
        projectDir: Path,
        customConfigName: String? = null,
    ): Path? {
        if (!customConfigName.isNullOrBlank()) {
            val customPath = resolveCustomConfigPath(projectDir, customConfigName)
            return if (Files.exists(customPath)) customPath else null
        }

        val envOverride = System.getenv(QodanaEnv.CONF)
        if (!envOverride.isNullOrBlank()) {
            val overridePath = resolveCustomConfigPath(projectDir, envOverride)
            if (Files.exists(overridePath)) return overridePath
        }
        val candidates = listOf("qodana.yaml", "qodana.yml")
        return candidates
            .map { projectDir.resolve(it) }
            .firstOrNull { Files.exists(it) }
    }

    private fun resolveCustomConfigPath(
        projectDir: Path,
        configuredPath: String,
    ): Path {
        val rawPath = Path.of(configuredPath)
        return if (rawPath.isAbsolute) rawPath else projectDir.resolve(rawPath)
    }

    fun merge(
        yaml: QodanaYaml?,
        context: ScanContext,
    ): ScanContext {
        if (yaml == null) return context

        return context.copy(
            profile = mergeProfile(yaml.profile, context.profile),
            runtime = mergeRuntime(yaml, context.runtime),
            report = mergeReport(yaml, context.report),
            docker = mergeDocker(yaml, context.docker),
            linter = context.linter ?: yaml.linter ?: yaml.image,
            yaml = yaml,
        )
    }

    private fun mergeProfile(
        yamlProfile: YamlProfile,
        cliProfile: ProfileSpec?,
    ): ProfileSpec =
        ProfileSpec(
            name = cliProfile?.name ?: yamlProfile.name.ifBlank { null },
            path = cliProfile?.path ?: yamlProfile.path.ifBlank { null },
        )

    private fun mergeRuntime(
        yaml: QodanaYaml,
        runtime: RuntimeContext,
    ): RuntimeContext {
        val mergedProperties = yaml.properties + runtime.properties
        val failThreshold = runtime.failThreshold ?: yaml.failThreshold
        val yamlDisableSanity = yaml.disableSanityInspections?.toBooleanStrictOrNull() == true
        return runtime.copy(
            properties = mergedProperties,
            failThreshold = failThreshold,
            disableSanity = runtime.disableSanity || yamlDisableSanity,
            runPromo = runtime.runPromo ?: yaml.runPromoInspections?.ifBlank { null },
            bootstrap = runtime.bootstrap ?: yaml.bootstrap?.ifBlank { null },
            fixesStrategy = runtime.fixesStrategy ?: yaml.fixesStrategy?.ifBlank { null },
            script =
                if (runtime.script == "default") {
                    yaml.script.name.ifBlank { runtime.script }
                } else {
                    runtime.script
                },
        )
    }

    private fun mergeReport(
        yaml: QodanaYaml,
        report: ReportOptions,
    ): ReportOptions =
        report.copy(
            baselineIncludeAbsent =
                report.baselineIncludeAbsent ||
                    yaml.includeAbsent?.toBooleanStrictOrNull() == true,
        )

    private fun mergeDocker(
        yaml: QodanaYaml,
        docker: DockerOptions,
    ): DockerOptions =
        docker.copy(
            image = docker.image ?: yaml.image ?: yaml.linter,
        )

    fun resolveBootstrap(yaml: QodanaYaml?): String? = yaml?.bootstrap

    fun resolvePlugins(yaml: QodanaYaml?): List<YamlPlugin> = yaml?.plugins ?: emptyList()

    fun resolveDotNet(yaml: QodanaYaml?): YamlDotNet? = yaml?.dotnet
}

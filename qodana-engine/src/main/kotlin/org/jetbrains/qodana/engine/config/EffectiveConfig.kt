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

    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(projectDir: Path): QodanaYaml? {
        val yamlFile = resolveYamlPath(projectDir) ?: return null
        return parse(Files.readString(yamlFile))
    }

    fun parse(yamlContent: String): QodanaYaml {
        return yamlMapper.readValue(yamlContent, QodanaYaml::class.java)
    }

    fun resolveYamlPath(projectDir: Path): Path? {
        val envOverride = System.getenv(QodanaEnv.CONF)
        if (!envOverride.isNullOrBlank()) {
            val overridePath = Path.of(envOverride)
            if (Files.exists(overridePath)) return overridePath
        }
        val candidates = listOf("qodana.yaml", "qodana.yml")
        return candidates
            .map { projectDir.resolve(it) }
            .firstOrNull { Files.exists(it) }
    }

    fun merge(yaml: QodanaYaml?, context: ScanContext): ScanContext {
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

    private fun mergeProfile(yamlProfile: YamlProfile, cliProfile: ProfileSpec?): ProfileSpec {
        return ProfileSpec(
            name = cliProfile?.name ?: yamlProfile.name.ifBlank { null },
            path = cliProfile?.path ?: yamlProfile.path.ifBlank { null },
        )
    }

    private fun mergeRuntime(yaml: QodanaYaml, runtime: RuntimeContext): RuntimeContext {
        val mergedProperties = yaml.properties + runtime.properties
        val failThreshold = runtime.failThreshold ?: yaml.failThreshold
        return runtime.copy(
            properties = mergedProperties,
            failThreshold = failThreshold,
        )
    }

    private fun mergeReport(yaml: QodanaYaml, report: ReportOptions): ReportOptions {
        return report.copy(
            baselineIncludeAbsent = report.baselineIncludeAbsent ||
                yaml.includeAbsent?.toBooleanStrictOrNull() == true,
        )
    }

    private fun mergeDocker(yaml: QodanaYaml, docker: DockerOptions): DockerOptions {
        return docker.copy(
            image = docker.image ?: yaml.image ?: yaml.linter,
        )
    }

    fun resolveBootstrap(yaml: QodanaYaml?): String? {
        return yaml?.bootstrap
    }

    fun resolvePlugins(yaml: QodanaYaml?): List<YamlPlugin> {
        return yaml?.plugins ?: emptyList()
    }

    fun resolveDotNet(yaml: QodanaYaml?): YamlDotNet? {
        return yaml?.dotnet
    }
}

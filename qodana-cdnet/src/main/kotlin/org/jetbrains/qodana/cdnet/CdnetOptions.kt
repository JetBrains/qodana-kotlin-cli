package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import java.nio.file.Path

object CdnetOptions {
    private val frameworkPrefixes = listOf("log.", "idea.", "qodana.", "jetbrains.")

    fun computeArgs(context: ThirdPartyScanContext): List<String> {
        val target = getSolutionOrProject(context)
            ?: error("Solution/project relative file path is not specified. Use --solution or --project flags or create qodana.yaml file with respective fields")

        val cltPath = context.customTools["clt"]
            ?: error("ReSharper CLT not found in mounted tools")

        val props = buildProperties(context)
        val sarifPath = context.paths.resultsDir.resolve("qodana.sarif.json")

        return buildList {
            add("dotnet")
            add(cltPath.toString())
            add("inspectcode")
            add(target)
            add("-o=\"$sarifPath\"")
            add("-f=\"Qodana\"")
            add("--LogFolder=\"${context.logDir}\"")
            if (props.isNotEmpty()) {
                add("--properties:$props")
            }
            if (context.noStatistics) {
                add("--telemetry-optout")
            }
            if (context.noBuild) {
                add("--no-build")
            }
        }
    }

    fun getSolutionOrProject(context: ThirdPartyScanContext): String? {
        return listOfNotNull(
            context.solutionPath,
            context.projectPath,
            context.yaml?.dotnet?.solution,
            context.yaml?.dotnet?.project,
        ).firstOrNull { it.isNotBlank() }
    }

    private fun buildProperties(context: ThirdPartyScanContext): String {
        val props = mutableListOf<String>()

        // Filter out framework-controlled properties
        for (p in context.properties) {
            if (frameworkPrefixes.any { p.startsWith(it) }) continue
            props.add(p)
        }

        // Configuration: CLI flag > YAML
        val config = context.configurationName
            ?: context.yaml?.dotnet?.configuration
        if (config != null) {
            props.add("Configuration=$config")
        }

        // Platform: CLI flag > YAML
        val platform = context.platformName
            ?: context.yaml?.dotnet?.platform
        if (platform != null) {
            props.add("Platform=$platform")
        }

        return props.joinToString(";")
    }
}

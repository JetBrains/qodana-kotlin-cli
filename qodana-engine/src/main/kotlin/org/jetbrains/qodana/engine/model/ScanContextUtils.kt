package org.jetbrains.qodana.engine.model

import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

object ScanContextUtils {

    /**
     * Determines the run scenario based on scan configuration.
     * Priority: fullHistory > no startHash (default) > forceLocal > container (default) > reversePr > scoped
     */
    fun determineRunScenario(
        fullHistory: Boolean,
        startHash: String?,
        forceLocalChanges: Boolean,
        isContainer: Boolean,
        reversePrAnalysis: Boolean,
    ): RunScenario {
        if (fullHistory) return RunScenario.FullHistory
        if (startHash.isNullOrEmpty()) return RunScenario.Default
        if (forceLocalChanges) return RunScenario.LocalChanges(diffStart = startHash)
        if (isContainer) return RunScenario.Default
        if (reversePrAnalysis) return RunScenario.ReverseScoped(targetBranch = startHash)
        return RunScenario.Scoped(targetBranch = startHash)
    }

    /**
     * Converts timeout in milliseconds to Duration. Zero or negative → max duration.
     */
    fun getAnalysisTimeout(timeoutMs: Long): Duration {
        return if (timeoutMs <= 0) Duration.ofMillis(Long.MAX_VALUE) else Duration.ofMillis(timeoutMs)
    }

    /**
     * Returns path to ide.vmoptions in the config directory.
     */
    fun vmOptionsPath(configDir: Path): Path = configDir.resolve("ide.vmoptions")

    /**
     * Returns path to install_plugins.vmoptions in the config directory.
     */
    fun installPluginsVmOptionsPath(configDir: Path): Path = configDir.resolve("install_plugins.vmoptions")

    /**
     * Checks if qodana.yaml exists in the given project directory.
     */
    fun localQodanaYamlExists(projectDir: Path): Boolean =
        projectDir.resolve("qodana.yaml").exists() || projectDir.resolve("qodana.yml").exists()

    /**
     * Calculates project directory path relative to repository root.
     * Returns "." if they are the same directory.
     */
    fun projectDirRelativeToRepoRoot(projectDir: Path, repoRoot: Path): String {
        val relative = repoRoot.relativize(projectDir).toString()
        return relative.ifEmpty { "." }
    }

    /**
     * Splits a list of property strings into key=value map and flags list.
     * "key=val" → property, "-flag" → flag. Splits on first '=' only.
     */
    fun parsePropertiesAndFlags(properties: List<String>): Pair<Map<String, String>, List<String>> {
        val propMap = mutableMapOf<String, String>()
        val flags = mutableListOf<String>()
        for (prop in properties) {
            val eqIndex = prop.indexOf('=')
            if (eqIndex >= 0) {
                propMap[prop.substring(0, eqIndex)] = prop.substring(eqIndex + 1)
            } else {
                flags.add(prop)
            }
        }
        return propMap to flags
    }

    /**
     * Checks if a scenario is scoped or reverse-scoped.
     */
    fun isScopedScenario(scenario: RunScenario): Boolean =
        scenario is RunScenario.Scoped || scenario is RunScenario.ReverseScoped
}

package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.engine.model.ScanContext
import org.jetbrains.qodana.engine.startup.DeviceId
import java.nio.file.Path

/**
 * Generates `idea.properties` and JVM options content for IDE execution
 * based on the current [ScanContext].
 */
object PropertyGenerator {
    private const val IDEA_PROPERTIES_FILE = "idea.properties"
    private const val VMOPTIONS_FILE = "idea64.vmoptions"

    /**
     * Core IDE properties derived from scan paths and runtime configuration.
     */
    fun generateIdeaProperties(context: ScanContext): String =
        buildString {
            val paths = context.paths

            // System directories – keep IDE state inside the results/cache tree
            appendProperty("idea.system.path", paths.cacheDir.resolve("idea-system"))
            appendProperty("idea.config.path", paths.cacheDir.resolve("idea-config"))
            appendProperty("idea.plugins.path", paths.cacheDir.resolve("idea-plugins"))
            appendProperty("idea.log.path", paths.resultsDir.resolve("log"))

            // Headless / non-interactive
            appendProperty("idea.is.internal", "false")
            appendProperty("idea.headless.enable.statistics", "false")

            // Suppress class-not-found warnings in headless mode
            appendProperty("idea.required.plugins.id", "")

            val deviceIdSalt = DeviceId.getDeviceIdSalt(remoteUrl = context.ci.remoteUrl ?: "")
            appendProperty("idea.headless.statistics.device.id", deviceIdSalt.deviceId)
            appendProperty("idea.headless.statistics.salt", deviceIdSalt.salt)

            context.runtime.analysisId
                ?.takeIf { it.isNotBlank() }
                ?.let { appendProperty("qodana.automation.guid", it) }

            context.runtime.coverageDir?.let { appendProperty("qodana.coverage.input", it) }

            val relativeToRepositoryRoot =
                runCatching {
                    context.paths.repositoryRoot
                        .relativize(context.paths.projectDir)
                        .toString()
                }.getOrNull()
            if (!relativeToRepositoryRoot.isNullOrBlank() && relativeToRepositoryRoot != ".") {
                appendProperty("qodana.path.to.project.dir.from.project.root", relativeToRepositoryRoot)
            }

            // Merge user-supplied properties (from CLI --property or qodana.yaml)
            val yamlProps = context.yaml?.properties.orEmpty()
            val runtimeProps = context.runtime.properties
            val merged = yamlProps + runtimeProps // runtime wins on conflict
            for ((key, value) in merged) {
                appendProperty(key, value)
            }
        }

    /**
     * Default JVM options for running the IDE in inspection mode.
     */
    fun generateVmOptions(context: ScanContext): String =
        buildString {
            // Memory defaults for inspection mode
            appendLine("-Xmx2048m")
            appendLine("-Xms256m")
            appendLine("-XX:+UseG1GC")
            appendLine("-XX:+HeapDumpOnOutOfMemoryError")

            // Headless AWT – required for Linux/macOS servers without a display
            appendLine("-Djava.awt.headless=true")

            // Debug port when requested
            context.runtime.jvmDebugPort?.let { port ->
                appendLine("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:$port")
            }

            for (flag in context.runtime.propertyFlags) {
                if (flag.isNotBlank()) {
                    appendLine(flag)
                }
            }
        }

    /**
     * Writes both `idea.properties` and `idea64.vmoptions` into [targetDir].
     */
    fun writeTo(
        context: ScanContext,
        targetDir: Path,
        writeFile: (Path, String) -> Unit,
    ) {
        writeFile(targetDir.resolve(IDEA_PROPERTIES_FILE), generateIdeaProperties(context))
        writeFile(targetDir.resolve(VMOPTIONS_FILE), generateVmOptions(context))
    }

    // ---- helpers ----

    private fun StringBuilder.appendProperty(
        key: String,
        value: Any,
    ) {
        appendLine("$key=$value")
    }
}

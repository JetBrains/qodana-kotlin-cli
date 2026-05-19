package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import org.jetbrains.qodana.cli.BuildInfo

class QodanaCommand : CliktCommand("qodana") {

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = "Qodana CLI — static analysis tool by JetBrains"

    private val logLevel by option("--log-level", help = "Set log-level for output")
        .default("error")
    private val disableUpdateChecks by option("--disable-update-checks", help = "Disable check for updates")
        .flag()

    init {
        versionOption(VERSION, names = setOf("--version", "-v"))
    }

    override fun run() {
        val normalizedLogLevel = logLevel.lowercase()
        if (normalizedLogLevel !in VALID_LOG_LEVELS) {
            throw UsageError("Unknown log level: $logLevel")
        }
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", normalizedLogLevel)
        System.setProperty(SYSTEM_DISABLE_UPDATE_CHECKS, disableUpdateChecks.toString())
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
        }
    }

    companion object {
        const val SYSTEM_DISABLE_UPDATE_CHECKS = "qodana.disableUpdateChecks"
        private val VALID_LOG_LEVELS = setOf("trace", "debug", "info", "warn", "error")
        // The build embeds the version into BuildInfo.VERSION via a generated source
        // file (see qodana-cli/build.gradle.kts). System property / env-var overrides
        // remain supported for local development and tests that don't go through Gradle.
        val VERSION: String = System.getProperty("qodana.version")
            ?: System.getenv("QODANA_VERSION")
            ?: BuildInfo.VERSION
    }
}

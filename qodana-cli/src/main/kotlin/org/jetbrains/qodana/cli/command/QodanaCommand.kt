package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption

class QodanaCommand : CliktCommand("qodana") {

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = "Qodana CLI — static analysis tool by JetBrains"

    private val logLevel by option("--log-level", "-l", help = "Log level (info, warn, debug, error)")
        .default("info")

    init {
        versionOption(VERSION)
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
        }
    }

    companion object {
        const val VERSION = "0.1.0-dev"
    }
}

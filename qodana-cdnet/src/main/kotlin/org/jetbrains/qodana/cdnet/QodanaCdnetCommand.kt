package org.jetbrains.qodana.cdnet

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.versionOption

class QodanaCdnetCommand : CliktCommand("qodana-cdnet") {
    override fun help(context: Context) = "Qodana for .NET (ReSharper InspectCode) v${Version.VALUE}"

    override val invokeWithoutSubcommand = true

    init {
        versionOption(Version.VALUE, names = setOf("--version", "-v"))
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(help(currentContext))
        }
    }
}

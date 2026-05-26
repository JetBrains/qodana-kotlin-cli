package org.jetbrains.qodana.cdnet

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class QodanaCdnetCommand : CliktCommand("qodana-cdnet") {
    override fun help(context: Context) = "Qodana for .NET (ReSharper InspectCode) v${BuildInfo.VERSION}"

    override val invokeWithoutSubcommand = true

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(help(currentContext))
        }
    }
}

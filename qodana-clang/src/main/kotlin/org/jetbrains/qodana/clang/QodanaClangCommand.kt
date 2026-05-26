package org.jetbrains.qodana.clang

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.versionOption

class QodanaClangCommand : CliktCommand("qodana-clang") {
    override fun help(context: Context) = "Qodana for C/C++ (clang-tidy) v${Version.VALUE}"

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

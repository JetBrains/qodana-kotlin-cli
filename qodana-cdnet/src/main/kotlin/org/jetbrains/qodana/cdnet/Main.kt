package org.jetbrains.qodana.cdnet

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.infra.fs.NioFileSystem
import org.jetbrains.qodana.infra.process.SystemProcessRunner
import org.jetbrains.qodana.infra.terminal.MordantTerminal

object Version {
    const val VALUE = "0.1.0-dev"
}

fun main(args: Array<String>) {
    val processRunner = SystemProcessRunner()
    val fileSystem = NioFileSystem()
    val terminal = MordantTerminal()

    val linter = CdnetLinter(processRunner, fileSystem, terminal)

    val rootCommand = QodanaCdnetCommand().subcommands(
        CdnetCommand(linter, terminal),
    )
    rootCommand.main(args)
}

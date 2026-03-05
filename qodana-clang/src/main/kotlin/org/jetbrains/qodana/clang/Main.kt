package org.jetbrains.qodana.clang

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.infra.fs.NioFileSystem
import org.jetbrains.qodana.infra.process.SystemProcessRunner
import org.jetbrains.qodana.infra.sarif.QodanaSarifService
import org.jetbrains.qodana.infra.terminal.MordantTerminal

object Version {
    const val VALUE = "0.1.0-dev"
}

fun main(args: Array<String>) {
    val processRunner = SystemProcessRunner()
    val sarifService = QodanaSarifService()
    val fileSystem = NioFileSystem()
    val terminal = MordantTerminal()

    val linter = ClangLinter(processRunner, sarifService, fileSystem, terminal)

    val rootCommand = QodanaClangCommand().subcommands(
        ClangCommand(linter, terminal),
    )
    rootCommand.main(args)
}

package org.jetbrains.qodana.clang

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.core.fs.NioFileSystem
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.core.terminal.MordantTerminal
import org.jetbrains.qodana.engine.startup.EapChecker
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val processRunner = SystemProcessRunner()
    val sarifService = QodanaSarifService()
    val fileSystem = NioFileSystem()
    val terminal = MordantTerminal()

    resolveThirdPartyEapExitCode(terminal)?.let { exitCode ->
        exitProcess(exitCode)
    }

    val linter = ClangLinter(processRunner, sarifService, fileSystem, terminal)

    val rootCommand =
        QodanaClangCommand().subcommands(
            ClangCommand(linter, terminal),
        )
    rootCommand.main(args)
}

internal fun resolveThirdPartyEapExitCode(
    terminal: Terminal,
    buildDateStr: String =
        System.getProperty("qodana.build.date")
            ?: System.getenv("QODANA_BUILD_DATE")
            ?: "",
    isEap: Boolean =
        (
            System.getProperty("qodana.is.eap")
                ?: System.getenv("QODANA_IS_EAP")
                ?: "false"
        ).toBooleanStrictOrNull() ?: false,
    checker: EapChecker = EapChecker(terminal),
): Int? {
    if (!isEap) return null
    if (buildDateStr.isBlank()) {
        terminal.error("Failed to parse build date")
        return ExitCode.EAP_EXPIRED.code
    }
    val result = checker.checkAndPrint(buildDateStr, isEap = true)
    return if (result.expired) result.exitCode else null
}

package com.jetbrains.qodana.infra.terminal

import com.github.ajalt.mordant.terminal.Terminal as MTerminal
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.jetbrains.qodana.core.port.Terminal

class MordantTerminal : Terminal {
    private val terminal = MTerminal()
    private var redactedTokens: Set<String> = emptySet()
    override var isCi: Boolean = false

    override val isInteractive: Boolean
        @Suppress("DEPRECATION")
        get() = terminal.info.interactive && !isCi

    override fun print(message: String) {
        terminal.print(redact(message))
    }

    override fun println(message: String) {
        terminal.println(redact(message))
    }

    override fun error(message: String) {
        terminal.println(TextColors.red(redact(message)))
    }

    override fun info(message: String) {
        terminal.println(redact(message))
    }

    override fun warn(message: String) {
        terminal.println(TextColors.yellow(redact(message)))
    }

    override fun debug(message: String) {
        terminal.println(TextColors.gray(redact(message)))
    }

    override fun <T> spinner(message: String, action: () -> T): T {
        // In CI mode or non-interactive, just print the message and run
        if (!isInteractive) {
            terminal.println(message)
            return action()
        }
        terminal.print("$message... ")
        val result = action()
        terminal.println("done")
        return result
    }

    override fun prompt(message: String, default: String?): String {
        val suffix = if (default != null) " [$default]" else ""
        terminal.print("$message$suffix: ")
        val input = readlnOrNull()?.trim() ?: ""
        return input.ifEmpty { default ?: "" }
    }

    override fun select(message: String, choices: List<String>): String {
        terminal.println(message)
        choices.forEachIndexed { index, choice ->
            terminal.println("  ${index + 1}. $choice")
        }
        terminal.print("Enter number: ")
        val input = readlnOrNull()?.trim()?.toIntOrNull() ?: 1
        return choices.getOrElse(input - 1) { choices.first() }
    }

    override fun setRedactedTokens(tokens: Set<String>) {
        redactedTokens = tokens.filter { it.isNotBlank() }.toSet()
    }

    private fun redact(message: String): String {
        var result = message
        for (token in redactedTokens) {
            result = result.replace(token, "***")
        }
        return result
    }
}

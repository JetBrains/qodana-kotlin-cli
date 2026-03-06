package org.jetbrains.qodana.core.terminal

import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.terminal.Terminal as MTerminal
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.calculateTimeElapsed
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.spinner as progressSpinner
import com.github.ajalt.mordant.widgets.progress.text as progressText
import org.jetbrains.qodana.core.port.Terminal

class MordantTerminal : Terminal {
    private val terminal = MTerminal()
    private var redactedTokens: Set<String> = emptySet()
    override var isCi: Boolean = false

    override val isInteractive: Boolean
        @Suppress("DEPRECATION")
        get() = terminal.info.interactive && !isCi

    override fun print(message: String) {
        // Use raw print so control sequences (\r, ANSI clear line) survive for progress rendering.
        terminal.rawPrint(redact(message))
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
        val redactedMessage = redact(message)
        if (!isInteractive) {
            terminal.println("$redactedMessage...")
            return action()
        }

        val definition = progressBarContextLayout<String>(
            spacing = 1,
            alignColumns = false,
            align = TextAlign.LEFT,
        ) {
            progressSpinner(Spinner.Lines())
            progressText(align = TextAlign.LEFT) { context }
            progressText(fps = 5, align = TextAlign.LEFT) {
                val elapsedSeconds = calculateTimeElapsed()?.inWholeSeconds ?: 0
                "(${elapsedSeconds}s)"
            }
        }
        val progress = definition.animateOnThread(
            terminal = terminal,
            context = "$redactedMessage...",
            total = null,
            clearWhenFinished = true,
        )
        val future = progress.execute()

        return try {
            action()
        } finally {
            progress.stop()
            future.cancel(true)
        }
    }

    override suspend fun <T> spinnerWithUpdates(message: String, action: suspend (Terminal.SpinnerHandle) -> T): T {
        val redactedMessage = redact(message)
        if (!isInteractive) {
            terminal.println("$redactedMessage...")
            return action(Terminal.SpinnerHandle { })
        }

        val definition = progressBarContextLayout<String>(
            spacing = 1,
            alignColumns = false,
            align = TextAlign.LEFT,
        ) {
            progressSpinner(Spinner.Lines())
            progressText(align = TextAlign.LEFT) { context }
            progressText(fps = 5, align = TextAlign.LEFT) {
                val elapsedSeconds = calculateTimeElapsed()?.inWholeSeconds ?: 0
                "(${elapsedSeconds}s)"
            }
        }
        val progress = definition.animateOnThread(
            terminal = terminal,
            context = "$redactedMessage...",
            total = null,
            clearWhenFinished = true,
        )
        val future = progress.execute()
        val handle = Terminal.SpinnerHandle { updated ->
            progress.update { context = "${redact(updated)}..." }
        }

        return try {
            action(handle)
        } finally {
            progress.stop()
            future.cancel(true)
        }
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

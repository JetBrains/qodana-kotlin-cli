package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.port.Terminal

/**
 * Writes raw analyzer/container stream chunks to terminal, preserving control characters
 * such as carriage returns used for in-place progress updates.
 */
internal class TerminalStreamRenderer(
    private val terminal: Terminal,
) {
    companion object {
        private const val CLEAR_LINE_PREFIX = "\r\u001B[2K"
    }

    private var lineEnded = true

    fun render(chunk: String) {
        if (chunk.isEmpty()) {
            return
        }
        val rendered = if (terminal.isInteractive) {
            chunk.replace("\r", CLEAR_LINE_PREFIX)
        } else {
            chunk
        }
        terminal.print(rendered)
        lineEnded = chunk.endsWith('\n') || chunk.endsWith('\r')
    }

    fun renderInPlace(text: String) {
        if (terminal.isInteractive) {
            terminal.print("$CLEAR_LINE_PREFIX$text")
            lineEnded = false
            return
        }
        terminal.println(text)
        lineEnded = true
    }

    fun ensureLineBreak() {
        if (!terminal.isInteractive) {
            return
        }
        if (!lineEnded) {
            terminal.println("")
            lineEnded = true
        }
    }
}

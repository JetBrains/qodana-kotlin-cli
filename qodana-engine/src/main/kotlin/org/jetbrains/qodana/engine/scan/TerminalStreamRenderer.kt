package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.port.Terminal

/**
 * Writes raw analyzer/container stream chunks to terminal, preserving control characters
 * such as carriage returns used for in-place progress updates.
 */
internal class TerminalStreamRenderer(
    private val terminal: Terminal,
) {
    private var lineEnded = true
    private var currentColumn = 0
    private var lineWidth = 0

    fun render(chunk: String) {
        if (chunk.isEmpty()) {
            return
        }
        terminal.print(chunk)
        if (!terminal.isInteractive) {
            lineEnded = chunk.endsWith('\n')
            return
        }
        updateLineState(chunk)
    }

    fun renderInPlace(text: String) {
        if (terminal.isInteractive) {
            val clear = if (lineWidth > 0) {
                "\r${" ".repeat(lineWidth)}\r"
            } else {
                "\r"
            }
            terminal.print("$clear$text")
            currentColumn = text.length
            lineWidth = text.length
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
            currentColumn = 0
            lineWidth = 0
        }
    }

    private fun updateLineState(chunk: String) {
        for (ch in chunk) {
            when (ch) {
                '\n' -> {
                    currentColumn = 0
                    lineWidth = 0
                    lineEnded = true
                }
                '\r' -> {
                    currentColumn = 0
                    lineEnded = false
                }
                else -> {
                    currentColumn += 1
                    if (currentColumn > lineWidth) {
                        lineWidth = currentColumn
                    }
                    lineEnded = false
                }
            }
        }
    }
}

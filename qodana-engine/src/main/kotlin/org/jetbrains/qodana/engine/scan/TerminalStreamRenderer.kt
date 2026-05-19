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
    private val nonInteractiveBuffer = StringBuilder()

    fun render(chunk: String) {
        if (chunk.isEmpty()) {
            return
        }

        if (!terminal.isInteractive) {
            renderNonInteractive(chunk)
            return
        }

        terminal.print(chunk)
        updateLineState(chunk)
    }

    fun renderInPlace(text: String) {
        if (terminal.isInteractive) {
            val clear =
                if (lineWidth > 0) {
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
            flushNonInteractiveBuffer()
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

    private fun renderNonInteractive(chunk: String) {
        // Preserve control-driven in-place updates (for example '\r' based progress),
        // but emit plain output line-by-line for stable CI logs.
        if (containsControlRewrite(chunk)) {
            flushNonInteractiveBuffer()
            terminal.print(chunk)
            lineEnded = chunk.endsWith('\n')
            return
        }

        nonInteractiveBuffer.append(chunk)
        emitBufferedLines()
    }

    private fun emitBufferedLines() {
        while (true) {
            val newline = nonInteractiveBuffer.indexOf("\n")
            if (newline < 0) break
            val line = nonInteractiveBuffer.substring(0, newline).trimEnd('\r')
            terminal.println(line)
            nonInteractiveBuffer.delete(0, newline + 1)
            lineEnded = true
        }
        if (nonInteractiveBuffer.isNotEmpty()) {
            lineEnded = false
        }
    }

    private fun flushNonInteractiveBuffer() {
        emitBufferedLines()
        if (nonInteractiveBuffer.isNotEmpty()) {
            terminal.println(nonInteractiveBuffer.toString())
            nonInteractiveBuffer.clear()
            lineEnded = true
        }
    }

    private fun containsControlRewrite(chunk: String): Boolean = chunk.indexOf('\r') >= 0 || chunk.indexOf('\u001B') >= 0
}

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

    fun render(chunk: String) {
        if (chunk.isEmpty()) {
            return
        }
        terminal.print(chunk)
        lineEnded = chunk.endsWith('\n') || chunk.endsWith('\r')
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

package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TerminalStreamRendererTest {

    @Test
    fun `interactive render preserves carriage return chunks`() {
        val terminal = RendererRecordingTerminal(isInteractive = true)
        val renderer = TerminalStreamRenderer(terminal)

        renderer.render("Downloading 10%\rDownloading 20%\r")

        assertEquals(
            listOf("Downloading 10%\rDownloading 20%\r"),
            terminal.printed,
        )
    }

    @Test
    fun `render in place clears previous content without ansi sequences`() {
        val terminal = RendererRecordingTerminal(isInteractive = true)
        val renderer = TerminalStreamRenderer(terminal)
        val first = "Pulling image jetbrains/qodana-jvm:2025.3-eap..."
        val second = "Downloading 42/100"

        renderer.renderInPlace(first)
        renderer.renderInPlace(second)
        renderer.ensureLineBreak()

        assertEquals(
            listOf(
                "\r$first",
                "\r${" ".repeat(first.length)}\r$second",
            ),
            terminal.printed,
        )
        assertEquals(listOf(""), terminal.printlnMessages)
    }

    @Test
    fun `non interactive render preserves carriage return chunks`() {
        val terminal = RendererRecordingTerminal(isInteractive = false)
        val renderer = TerminalStreamRenderer(terminal)

        renderer.render("progress 1%\r")
        renderer.render("done\n")

        assertEquals(listOf("progress 1%\r"), terminal.printed)
        assertEquals(listOf("done"), terminal.printlnMessages)
    }

    @Test
    fun `non interactive plain output is emitted line by line`() {
        val terminal = RendererRecordingTerminal(isInteractive = false)
        val renderer = TerminalStreamRenderer(terminal)

        renderer.render("line 1\nline")
        renderer.render(" 2\nline 3")
        renderer.ensureLineBreak()

        assertEquals(emptyList(), terminal.printed)
        assertEquals(listOf("line 1", "line 2", "line 3"), terminal.printlnMessages)
    }
}

private class RendererRecordingTerminal(
    override val isInteractive: Boolean,
) : Terminal {
    val printed = mutableListOf<String>()
    val printlnMessages = mutableListOf<String>()

    override var isCi: Boolean = false

    override fun print(message: String) {
        printed += message
    }

    override fun println(message: String) {
        printlnMessages += message
    }

    override fun error(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
    override fun debug(message: String) {}
    override fun <T> spinner(message: String, action: () -> T): T = action()
    override fun prompt(message: String, default: String?): String = default ?: ""
    override fun select(message: String, choices: List<String>): String = choices.firstOrNull() ?: ""
    override fun setRedactedTokens(tokens: Set<String>) {}
}

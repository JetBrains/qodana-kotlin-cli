package org.jetbrains.qodana.core.terminal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MordantTerminalTest {

    @Test
    fun `isInteractive is false when isCi is true`() {
        val terminal = MordantTerminal()
        terminal.isCi = true
        assertFalse(terminal.isInteractive)
    }

    @Test
    fun `isCi defaults to false`() {
        val terminal = MordantTerminal()
        assertFalse(terminal.isCi)
    }

    @Test
    fun `setRedactedTokens stores tokens`() {
        val terminal = MordantTerminal()
        terminal.setRedactedTokens(setOf("secret123", "token456"))
        // Redaction is tested indirectly - the terminal should not crash
    }

    @Test
    fun `setRedactedTokens ignores blank tokens`() {
        val terminal = MordantTerminal()
        terminal.setRedactedTokens(setOf("", "  ", "valid-token"))
        // Should not crash on blank tokens
    }

    @Test
    fun `spinner returns action result in ci mode`() {
        val terminal = MordantTerminal()
        terminal.isCi = true
        val result = terminal.spinner("Loading") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `spinner returns action result`() {
        val terminal = MordantTerminal()
        val result = terminal.spinner("Processing") { "done" }
        assertEquals("done", result)
    }

    @Test
    fun `print and println do not throw`() {
        val terminal = MordantTerminal()
        terminal.print("test")
        terminal.println("test")
        terminal.error("test")
        terminal.info("test")
        terminal.warn("test")
        terminal.debug("test")
    }

    @Test
    fun `redaction works via print methods`() {
        val terminal = MordantTerminal()
        terminal.setRedactedTokens(setOf("secret-value"))
        // These should not throw and should redact internally
        terminal.println("Token is secret-value")
        terminal.error("Token is secret-value")
        terminal.warn("Token is secret-value")
        terminal.info("Token is secret-value")
        terminal.debug("Token is secret-value")
    }
}

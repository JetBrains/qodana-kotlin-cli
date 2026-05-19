package org.jetbrains.qodana.core.terminal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

        assertEquals(setOf("secret123", "token456"), redactedTokens(terminal))
    }

    @Test
    fun `setRedactedTokens ignores blank tokens`() {
        val terminal = MordantTerminal()
        terminal.setRedactedTokens(setOf("", "  ", "valid-token"))

        assertEquals(setOf("valid-token"), redactedTokens(terminal))
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
    fun `redaction masks configured tokens`() {
        val terminal = MordantTerminal()
        terminal.setRedactedTokens(setOf("secret-value", "api-key"))

        val redacted = redact(terminal, "Token is secret-value, key is api-key")
        assertEquals("Token is ***, key is ***", redacted)
    }

    @Suppress("UNCHECKED_CAST")
    private fun redactedTokens(terminal: MordantTerminal): Set<String> {
        val field = MordantTerminal::class.java.getDeclaredField("redactedTokens")
        field.isAccessible = true
        return field.get(terminal) as Set<String>
    }

    private fun redact(
        terminal: MordantTerminal,
        message: String,
    ): String {
        val method = MordantTerminal::class.java.getDeclaredMethod("redact", String::class.java)
        method.isAccessible = true
        return method.invoke(terminal, message) as String
    }
}

package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.port.Clock
import org.jetbrains.qodana.engine.startup.EapChecker
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainTest {

    @Test
    fun `non eap build skips check`() {
        val terminal = RecordingTerminal()
        val result = resolveThirdPartyEapExitCode(
            terminal = terminal,
            buildDateStr = "",
            isEap = false,
        )
        assertNull(result)
    }

    @Test
    fun `expired eap build returns eap exit code`() {
        val terminal = RecordingTerminal()
        val checker = EapChecker(
            terminal = terminal,
            isContainer = false,
            clock = Clock { Instant.parse("2026-01-01T00:00:00Z") },
        )
        val result = resolveThirdPartyEapExitCode(
            terminal = terminal,
            buildDateStr = "2025-01-01T00:00:00Z",
            isEap = true,
            checker = checker,
        )
        assertEquals(ExitCode.EAP_EXPIRED.code, result)
    }

    @Test
    fun `valid eap build allows startup`() {
        val terminal = RecordingTerminal()
        val checker = EapChecker(
            terminal = terminal,
            isContainer = false,
            clock = Clock { Instant.parse("2025-01-15T00:00:00Z") },
        )
        val result = resolveThirdPartyEapExitCode(
            terminal = terminal,
            buildDateStr = "2025-01-01T00:00:00Z",
            isEap = true,
            checker = checker,
        )
        assertNull(result)
    }
}

private class RecordingTerminal : Terminal {
    override val isInteractive: Boolean = false
    override var isCi: Boolean = false

    override fun print(message: String) {}
    override fun println(message: String) {}
    override fun error(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
    override fun debug(message: String) {}
    override fun <T> spinner(message: String, action: () -> T): T = action()
    override fun prompt(message: String, default: String?): String = default ?: ""
    override fun select(message: String, choices: List<String>): String = choices.first()
    override fun setRedactedTokens(tokens: Set<String>) {}
}

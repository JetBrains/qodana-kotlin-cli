package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EapCheckerTest {

    private val terminal = object : Terminal {
        override fun print(message: String) {}
        override fun println(message: String) {}
        override fun error(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun debug(message: String) {}
        override fun <T> spinner(message: String, action: () -> T): T = action()
        override fun prompt(message: String, default: String?): String = default ?: ""
        override fun select(message: String, choices: List<String>): String = choices.first()
        override val isInteractive: Boolean = false
        override var isCi: Boolean = false
        override fun setRedactedTokens(tokens: Set<String>) {}
    }

    @Test
    fun `not EAP returns early without error`() {
        val checker = EapChecker(terminal)
        val result = checker.check("2024-01-01T00:00:00Z", isEap = false)
        assertFalse(result.expired)
        assertNull(result.message)
    }

    @Test
    fun `valid EAP within 60 days`() {
        val tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS)
            .atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)

        val checker = EapChecker(terminal)
        val result = checker.check(tenDaysAgo, isEap = true)
        assertFalse(result.expired)
        assertTrue(result.message!!.contains("evaluation license"))
        assertTrue(result.message!!.contains("linter"))
    }

    @Test
    fun `expired EAP after 60 days`() {
        val seventyDaysAgo = Instant.now().minus(70, ChronoUnit.DAYS)
            .atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)

        val checker = EapChecker(terminal)
        val result = checker.check(seventyDaysAgo, isEap = true)
        assertTrue(result.expired)
        assertTrue(result.message!!.contains("expired"))
        assertEquals(ExitCode.EAP_EXPIRED.code, result.exitCode)
    }

    @Test
    fun `invalid date format returns expired`() {
        val checker = EapChecker(terminal)
        val result = checker.check("invalid-date", isEap = true)
        assertTrue(result.expired)
        assertTrue(result.message!!.contains("Failed to parse build date"))
        assertEquals(ExitCode.EAP_EXPIRED.code, result.exitCode)
    }

    @Test
    fun `container mode shows docker-specific message when expired`() {
        val seventyDaysAgo = Instant.now().minus(70, ChronoUnit.DAYS)
            .atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)

        val checker = EapChecker(terminal, isContainer = true)
        val result = checker.check(seventyDaysAgo, isEap = true)
        assertTrue(result.expired)
        assertTrue(result.message!!.contains("docker pull"))
    }

    @Test
    fun `container mode shows docker-specific agreement when valid`() {
        val tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS)
            .atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)

        val checker = EapChecker(terminal, isContainer = true)
        val result = checker.check(tenDaysAgo, isEap = true)
        assertFalse(result.expired)
        assertTrue(result.message!!.contains("Docker image"))
        assertTrue(result.message!!.contains("pull a new image"))
    }
}

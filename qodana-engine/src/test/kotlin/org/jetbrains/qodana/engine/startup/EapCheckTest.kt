package org.jetbrains.qodana.engine.startup

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertIs

class EapCheckTest {
    @Test
    fun `not EAP returns NotEap`() {
        val result = EapCheck.checkEap(isEap = false, buildDateStr = "2025-01-01T00:00:00Z")
        assertIs<EapCheck.EapResult.NotEap>(result)
    }

    @Test
    fun `valid EAP within 60 days`() {
        val buildDate = Instant.now().minus(10, ChronoUnit.DAYS)
        val result = EapCheck.checkEap(isEap = true, buildDateStr = buildDate.toString())
        assertIs<EapCheck.EapResult.Valid>(result)
    }

    @Test
    fun `expired EAP after 60 days`() {
        val buildDate = Instant.now().minus(70, ChronoUnit.DAYS)
        val result = EapCheck.checkEap(isEap = true, buildDateStr = buildDate.toString())
        assertIs<EapCheck.EapResult.Expired>(result)
    }

    @Test
    fun `invalid date format`() {
        val result = EapCheck.checkEap(isEap = true, buildDateStr = "not-a-date")
        assertIs<EapCheck.EapResult.InvalidDate>(result)
    }

    @Test
    fun `exactly at deadline is still valid`() {
        val buildDate = Instant.parse("2025-01-01T00:00:00Z")
        val deadline = buildDate.plus(60, ChronoUnit.DAYS)
        val result = EapCheck.checkEap(isEap = true, buildDateStr = buildDate.toString(), now = deadline)
        assertIs<EapCheck.EapResult.Valid>(result)
    }

    @Test
    fun `one second after deadline is expired`() {
        val buildDate = Instant.parse("2025-01-01T00:00:00Z")
        val deadline = buildDate.plus(60, ChronoUnit.DAYS).plusSeconds(1)
        val result = EapCheck.checkEap(isEap = true, buildDateStr = buildDate.toString(), now = deadline)
        assertIs<EapCheck.EapResult.Expired>(result)
    }
}

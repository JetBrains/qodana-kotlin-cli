package org.jetbrains.qodana.engine.scan

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportPortTest {
    @Test
    fun `showReportPort defined`() {
        assertEquals(9001, ReportPort.getShowReportPort(showReportPort = 9001, port = null))
    }

    @Test
    fun `both defined showReportPort wins`() {
        assertEquals(9003, ReportPort.getShowReportPort(showReportPort = 9003, port = 9002))
    }

    @Test
    fun `only port defined`() {
        assertEquals(9004, ReportPort.getShowReportPort(showReportPort = null, port = 9004))
    }

    @Test
    fun `no flags returns default`() {
        assertEquals(8080, ReportPort.getShowReportPort(showReportPort = null, port = null))
    }

    @Test
    fun `zero showReportPort falls through to port`() {
        assertEquals(9005, ReportPort.getShowReportPort(showReportPort = 0, port = 9005))
    }

    @Test
    fun `both zero returns default`() {
        assertEquals(8080, ReportPort.getShowReportPort(showReportPort = 0, port = 0))
    }
}

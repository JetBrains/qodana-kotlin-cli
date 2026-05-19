package org.jetbrains.qodana.core.model

import kotlin.test.*

class ExitCodeTest {
    @Test
    fun `SUCCESS code is 0`() {
        assertEquals(0, ExitCode.SUCCESS.code)
    }

    @Test
    fun `FAIL_THRESHOLD code is 255`() {
        assertEquals(255, ExitCode.FAIL_THRESHOLD.code)
    }

    @Test
    fun `THRESHOLD_REACHED code is 2`() {
        assertEquals(2, ExitCode.THRESHOLD_REACHED.code)
    }

    @Test
    fun `EAP_EXPIRED code is 7`() {
        assertEquals(7, ExitCode.EAP_EXPIRED.code)
    }

    @Test
    fun `all exit codes have unique values`() {
        val codes = ExitCode.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "Exit code values must be unique")
    }
}

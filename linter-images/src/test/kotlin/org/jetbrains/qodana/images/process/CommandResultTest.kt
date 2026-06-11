package org.jetbrains.qodana.images.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandResultTest {
    @Test
    fun `exit code zero is success`() {
        val r = CommandResult(exitCode = 0, stdout = "ok", stderr = "")
        assertTrue(r.isSuccess)
    }

    @Test
    fun `nonzero exit code is failure`() {
        val r = CommandResult(exitCode = 2, stdout = "", stderr = "boom")
        assertFalse(r.isSuccess)
        assertEquals("boom", r.stderr)
    }
}

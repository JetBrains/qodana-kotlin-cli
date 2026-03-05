package org.jetbrains.qodana.core.model

import kotlin.test.*

class ProcessResultTest {

    @Test
    fun `isSuccess is true when exitCode is 0`() {
        val result = ProcessResult(exitCode = 0, stdout = "ok", stderr = "")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `isSuccess is false when exitCode is non-zero`() {
        val result = ProcessResult(exitCode = 1, stdout = "", stderr = "error")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `data class equality works`() {
        val a = ProcessResult(exitCode = 0, stdout = "output", stderr = "")
        val b = ProcessResult(exitCode = 0, stdout = "output", stderr = "")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}

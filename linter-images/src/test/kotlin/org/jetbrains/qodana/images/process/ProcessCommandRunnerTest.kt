package org.jetbrains.qodana.images.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessCommandRunnerTest {
    private val runner = ProcessCommandRunner()

    @Test
    fun `captures stdout and exit code`() {
        val result = runner.run(listOf("sh", "-c", "printf hello"))
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `captures stderr separately and nonzero exit`() {
        val result = runner.run(listOf("sh", "-c", "printf oops 1>&2; exit 3"))
        assertEquals(3, result.exitCode)
        assertEquals("oops", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `concurrent drain handles large output on both streams without deadlock`() {
        // Each stream emits ~1MB — far past a typical 64KB pipe buffer. A sequential
        // reader would block the child on the unread stream; concurrent drain must not.
        val script = "yes a | head -c 1000000; yes b | head -c 1000000 1>&2"
        val result = runner.run(listOf("sh", "-c", script))
        assertEquals(0, result.exitCode)
        assertEquals(1_000_000, result.stdout.length)
        assertEquals(1_000_000, result.stderr.length)
    }
}

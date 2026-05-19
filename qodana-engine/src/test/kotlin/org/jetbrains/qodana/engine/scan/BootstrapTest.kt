package org.jetbrains.qodana.engine.scan

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootstrapTest {
    @Test
    fun `empty command returns 0`(
        @TempDir dir: Path,
    ) {
        assertEquals(0, Bootstrap.execute("", dir))
    }

    @Test
    fun `blank command returns 0`(
        @TempDir dir: Path,
    ) {
        assertEquals(0, Bootstrap.execute("  ", dir))
    }

    @Test
    fun `echo command succeeds`(
        @TempDir dir: Path,
    ) {
        val exitCode = Bootstrap.execute("echo hello", dir)
        assertEquals(0, exitCode)
    }

    @Test
    fun `capture stdout`(
        @TempDir dir: Path,
    ) {
        val (exitCode, stdout, stderr) = Bootstrap.executeWithCapture("echo test", dir)
        assertEquals(0, exitCode)
        assertTrue(stdout.trim().contains("test"))
    }

    @Test
    fun `capture stderr`(
        @TempDir dir: Path,
    ) {
        val (exitCode, stdout, stderr) = Bootstrap.executeWithCapture("echo test >&2", dir)
        assertEquals(0, exitCode)
        assertTrue(stderr.trim().contains("test"))
    }

    @Test
    fun `empty capture returns empty`(
        @TempDir dir: Path,
    ) {
        val (exitCode, stdout, stderr) = Bootstrap.executeWithCapture("", dir)
        assertEquals(0, exitCode)
        assertEquals("", stdout)
        assertEquals("", stderr)
    }
}

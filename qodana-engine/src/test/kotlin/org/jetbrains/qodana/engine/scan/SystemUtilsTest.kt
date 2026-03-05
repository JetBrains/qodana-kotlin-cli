package org.jetbrains.qodana.engine.scan

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemUtilsTest {

    @Test
    fun `isHomeDirectory true for home`() {
        val home = System.getProperty("user.home")
        assertTrue(SystemUtils.isHomeDirectory(home))
    }

    @Test
    fun `isHomeDirectory false for subdir`() {
        val home = System.getProperty("user.home")
        assertFalse(SystemUtils.isHomeDirectory("$home/subdir"))
    }

    @Test
    fun `isHomeDirectory false for tmp`(@TempDir dir: Path) {
        assertFalse(SystemUtils.isHomeDirectory(dir.toString()))
    }

    @Test
    fun `isHomeDirectory false for nonexistent`() {
        assertFalse(SystemUtils.isHomeDirectory("/nonexistent/path/xyz"))
    }

    @Test
    fun `getScanStages has 6 stages`() {
        val stages = SystemUtils.getScanStages()
        assertEquals(6, stages.size)
        assertTrue(stages[0].contains("Docker"))
        assertTrue(stages[5].contains("report"))
    }

    @Test
    fun `checkForUpdates dev version skips`() {
        assertNull(SystemUtils.checkForUpdates("dev") { "2.0.0" })
    }

    @Test
    fun `checkForUpdates nightly version skips`() {
        assertNull(SystemUtils.checkForUpdates("1.0.0-nightly") { "2.0.0" })
    }

    @Test
    fun `checkForUpdates returns new version`() {
        val result = SystemUtils.checkForUpdates("1.0.0") { "2.0.0" }
        assertEquals("2.0.0", result)
    }

    @Test
    fun `checkForUpdates strips v prefix`() {
        val result = SystemUtils.checkForUpdates("1.0.0") { "v2.0.0" }
        assertEquals("2.0.0", result)
    }

    @Test
    fun `checkForUpdates same version returns null`() {
        assertNull(SystemUtils.checkForUpdates("1.0.0") { "1.0.0" })
    }

    @Test
    fun `checkForUpdates empty latest returns null`() {
        assertNull(SystemUtils.checkForUpdates("1.0.0") { "" })
    }
}

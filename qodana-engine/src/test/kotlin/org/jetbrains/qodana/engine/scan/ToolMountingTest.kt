package org.jetbrains.qodana.engine.scan

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolMountingTest {

    @Test
    fun `getToolsMountPath creates directory`(@TempDir dir: Path) {
        val toolsPath = ToolMounting.getToolsMountPath(dir)
        assertTrue(toolsPath.exists())
        assertTrue(toolsPath.toString().endsWith("tools"))
    }

    @Test
    fun `processAuxiliaryTool creates file`(@TempDir dir: Path) {
        val content = "test content".toByteArray()
        val path = ToolMounting.processAuxiliaryTool("test.jar", "test", dir, content)
        assertTrue(path.exists())
        assertEquals("test content", String(path.readBytes()))
    }

    @Test
    fun `processAuxiliaryTool returns same path on re-run`(@TempDir dir: Path) {
        val content = "test content".toByteArray()
        val path1 = ToolMounting.processAuxiliaryTool("test.jar", "test", dir, content)
        val path2 = ToolMounting.processAuxiliaryTool("test.jar", "test", dir, content)
        assertEquals(path1, path2)
    }

    @Test
    fun `isInDirectory same dir`() {
        assertTrue(ToolMounting.isInDirectory("/a/b", "/a/b/file.txt"))
    }

    @Test
    fun `isInDirectory subdirectory`() {
        assertTrue(ToolMounting.isInDirectory("/a/b", "/a/b/c/file.txt"))
    }

    @Test
    fun `isInDirectory different dir`() {
        assertFalse(ToolMounting.isInDirectory("/a/b", "/a/c/file.txt"))
    }

    @Test
    fun `isInDirectory parent dir`() {
        assertFalse(ToolMounting.isInDirectory("/a/b/c", "/a/b/file.txt"))
    }
}

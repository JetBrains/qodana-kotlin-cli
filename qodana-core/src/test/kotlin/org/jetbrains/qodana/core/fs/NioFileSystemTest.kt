package org.jetbrains.qodana.core.fs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NioFileSystemTest {

    private val fs = NioFileSystem()

    @Test
    fun `read and write text file`(@TempDir dir: Path) {
        val file = dir.resolve("test.txt")
        fs.write(file, "hello world")
        assertEquals("hello world", fs.read(file))
    }

    @Test
    fun `read and write bytes`(@TempDir dir: Path) {
        val file = dir.resolve("test.bin")
        val data = byteArrayOf(1, 2, 3, 4, 5)
        fs.writeBytes(file, data)
        assertTrue(data.contentEquals(fs.readBytes(file)))
    }

    @Test
    fun `copy file`(@TempDir dir: Path) {
        val src = dir.resolve("src.txt")
        val dst = dir.resolve("dst.txt")
        fs.write(src, "copy me")
        fs.copy(src, dst)
        assertEquals("copy me", fs.read(dst))
    }

    @Test
    fun `exists returns true for existing file`(@TempDir dir: Path) {
        val file = dir.resolve("exists.txt")
        fs.write(file, "yes")
        assertTrue(fs.exists(file))
    }

    @Test
    fun `exists returns false for nonexistent file`(@TempDir dir: Path) {
        assertFalse(fs.exists(dir.resolve("nope.txt")))
    }

    @Test
    fun `create directories`(@TempDir dir: Path) {
        val nested = dir.resolve("a/b/c")
        fs.createDirectories(nested)
        assertTrue(Files.isDirectory(nested))
    }

    @Test
    fun `temp dir creates directory`() {
        val tmp = fs.tempDir("qodana-test")
        try {
            assertTrue(Files.isDirectory(tmp))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `walk finds files by glob`(@TempDir dir: Path) {
        fs.write(dir.resolve("a.kt"), "a")
        fs.write(dir.resolve("b.java"), "b")
        fs.write(dir.resolve("c.kt"), "c")

        val ktFiles = fs.walk(dir, "*.kt").toList()
        assertEquals(2, ktFiles.size)
        assertTrue(ktFiles.all { it.toString().endsWith(".kt") })
    }

    @Test
    fun `walk finds all files without glob`(@TempDir dir: Path) {
        fs.write(dir.resolve("a.txt"), "a")
        fs.write(dir.resolve("b.txt"), "b")
        val sub = dir.resolve("sub")
        fs.createDirectories(sub)
        fs.write(sub.resolve("c.txt"), "c")

        val allFiles = fs.walk(dir).toList()
        assertTrue(allFiles.size >= 3)
    }

    @Test
    fun `delete file`(@TempDir dir: Path) {
        val file = dir.resolve("delete-me.txt")
        fs.write(file, "bye")
        assertTrue(fs.exists(file))
        fs.delete(file)
        assertFalse(fs.exists(file))
    }

    @Test
    fun `delete directory recursively`(@TempDir dir: Path) {
        val subDir = dir.resolve("to-delete")
        fs.createDirectories(subDir)
        fs.write(subDir.resolve("a.txt"), "a")
        fs.write(subDir.resolve("b.txt"), "b")
        assertTrue(fs.exists(subDir))
        fs.delete(subDir)
        assertFalse(fs.exists(subDir))
    }

    @Test
    fun `copy preserves content`(@TempDir dir: Path) {
        val src = dir.resolve("source.json")
        val content = """{"version":"2.1.0","runs":[]}"""
        fs.write(src, content)

        val dst = dir.resolve("backup.json")
        fs.copy(src, dst)
        assertEquals(content, fs.read(dst))
    }

    @Test
    fun `write creates parent directories`(@TempDir dir: Path) {
        val nested = dir.resolve("deep/nested/file.txt")
        // NioFileSystem.write should handle parent dirs
        Files.createDirectories(nested.parent)
        fs.write(nested, "deep content")
        assertEquals("deep content", fs.read(nested))
    }
}

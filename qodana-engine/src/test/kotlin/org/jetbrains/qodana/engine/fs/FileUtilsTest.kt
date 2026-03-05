package org.jetbrains.qodana.engine.fs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileUtilsTest {

    @Test
    fun `getSha256 produces consistent hash`() {
        val content = "test content"
        val hash1 = FileUtils.getSha256(content.byteInputStream())
        val hash2 = FileUtils.getSha256(content.byteInputStream())
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // SHA-256 is 64 hex chars
    }

    @Test
    fun `getSha256 different content different hash`() {
        val hash1 = FileUtils.getSha256("hello".byteInputStream())
        val hash2 = FileUtils.getSha256("world".byteInputStream())
        assertTrue(hash1 != hash2)
    }

    @Test
    fun `getFileSha256 reads file`(@TempDir dir: Path) {
        val file = dir.resolve("test.txt")
        file.writeText("test content")

        val result = FileUtils.getFileSha256(file)
        assertTrue(result.isSuccess)
        assertEquals(64, result.getOrThrow().length)
    }

    @Test
    fun `getFileSha256 nonexistent file fails`(@TempDir dir: Path) {
        val result = FileUtils.getFileSha256(dir.resolve("nonexistent.txt"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `copyDir copies files recursively`(@TempDir dir: Path) {
        val src = dir.resolve("src")
        src.createDirectories()
        src.resolve("file1.txt").writeText("content1")
        src.resolve("subdir").createDirectories()
        src.resolve("subdir/file2.txt").writeText("content2")

        val dst = dir.resolve("dst")
        FileUtils.copyDir(src, dst)

        assertEquals("content1", dst.resolve("file1.txt").readText())
        assertEquals("content2", dst.resolve("subdir/file2.txt").readText())
    }

    @Test
    fun `copyDir preserves directory structure`(@TempDir dir: Path) {
        val src = dir.resolve("src")
        src.resolve("a/b/c").createDirectories()
        src.resolve("a/b/c/deep.txt").writeText("deep")

        val dst = dir.resolve("dst")
        FileUtils.copyDir(src, dst)

        assertEquals("deep", dst.resolve("a/b/c/deep.txt").readText())
    }

    @Test
    fun `sha256 known value`() {
        // SHA-256 of empty string is well-known
        val hash = FileUtils.getSha256("".byteInputStream())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }
}

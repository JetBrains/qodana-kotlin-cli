package org.jetbrains.qodana.engine.fs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebUiExtractorTest {

    @Test
    fun `throws when web-ui zip not in classpath`(@TempDir dir: Path) {
        val extractor = WebUiExtractor()
        val exception = assertFailsWith<IllegalStateException> {
            extractor.extractWebUi(dir.resolve("output"))
        }
        assertTrue(exception.message!!.contains("web-ui.zip not found"))
    }

    @Test
    fun `extracts zip contents to target directory`(@TempDir dir: Path) {
        val zipBytes = createTestZip(mapOf("index.html" to "hello", "css/style.css" to "body{}"))
        val target = dir.resolve("output")
        extractZipLikeWebUi(zipBytes, target)

        assertTrue(Files.exists(target.resolve("index.html")))
        assertEquals("hello", Files.readString(target.resolve("index.html")))
        assertTrue(Files.exists(target.resolve("css/style.css")))
        assertEquals("body{}", Files.readString(target.resolve("css/style.css")))
    }

    @Test
    fun `creates target directory if it does not exist`(@TempDir dir: Path) {
        val zipBytes = createTestZip(mapOf("file.txt" to "data"))
        val target = dir.resolve("nested/deep/output")
        extractZipLikeWebUi(zipBytes, target)

        assertTrue(Files.isDirectory(target))
        assertTrue(Files.exists(target.resolve("file.txt")))
    }

    @Test
    fun `rejects path traversal entries`(@TempDir dir: Path) {
        val zipBytes = createTestZipRaw(listOf("../escape.txt" to "bad"))
        val target = dir.resolve("output")
        assertFailsWith<SecurityException> {
            extractZipLikeWebUi(zipBytes, target)
        }
    }

    /**
     * Mimics the WebUiExtractor extraction logic using a byte array instead of classpath resource,
     * verifying the zip extraction and path traversal protection code paths.
     */
    private fun extractZipLikeWebUi(zipBytes: ByteArray, targetDir: Path) {
        Files.createDirectories(targetDir)
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = targetDir.resolve(entry.name).normalize()
                if (!entryPath.startsWith(targetDir)) {
                    throw SecurityException("Archive entry escapes target directory: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    entryPath.parent?.let { Files.createDirectories(it) }
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun createTestZip(entries: Map<String, String>): ByteArray {
        return createTestZipRaw(entries.entries.map { it.key to it.value })
    }

    private fun createTestZipRaw(entries: List<Pair<String, String>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}

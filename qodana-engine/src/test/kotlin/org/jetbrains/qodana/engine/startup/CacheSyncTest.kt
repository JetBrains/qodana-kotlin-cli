package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheSyncTest {

    private val terminal = object : Terminal {
        val messages = mutableListOf<String>()
        override fun print(message: String) {}
        override fun println(message: String) {}
        override fun error(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun debug(message: String) { messages.add(message) }
        override fun <T> spinner(message: String, action: () -> T): T = action()
        override fun prompt(message: String, default: String?): String = default ?: ""
        override fun select(message: String, choices: List<String>): String = choices.first()
        override val isInteractive: Boolean = false
        override var isCi: Boolean = false
        override fun setRedactedTokens(tokens: Set<String>) {}
    }

    private fun realFs() = object : FileSystem {
        override fun read(path: Path) = Files.readString(path)
        override fun readBytes(path: Path) = Files.readAllBytes(path)
        override fun write(path: Path, content: String) {
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
        override fun writeBytes(path: Path, content: ByteArray) { Files.write(path, content) }
        override fun copy(source: Path, target: Path) {
            if (Files.isDirectory(source)) {
                source.toFile().copyRecursively(target.toFile(), overwrite = false)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(source, target)
            }
        }
        override fun walk(root: Path, glob: String?): Sequence<Path> {
            val stream = Files.walk(root)
            return stream.iterator().asSequence()
        }
        override fun exists(path: Path) = Files.exists(path)
        override fun createDirectories(path: Path): Path = Files.createDirectories(path)
        override fun tempDir(prefix: String) = Files.createTempDirectory(prefix)
        override fun delete(path: Path) { path.toFile().deleteRecursively() }
        override fun extractArchive(archive: Path, target: Path) {}
    }

    @Test
    fun `syncIdeaCache copies idea dir from cache to project`(@TempDir tmpDir: Path) {
        val cacheDir = tmpDir.resolve("cache")
        val projectDir = tmpDir.resolve("project")
        Files.createDirectories(cacheDir.resolve(".idea"))
        Files.writeString(cacheDir.resolve(".idea").resolve("cached.xml"), "cached content")
        Files.createDirectories(projectDir)

        val sync = CacheSync(realFs(), terminal)
        sync.syncIdeaCache(cacheDir, projectDir)

        assertTrue(Files.exists(projectDir.resolve(".idea").resolve("cached.xml")))
        assertEquals("cached content", Files.readString(projectDir.resolve(".idea").resolve("cached.xml")))
    }

    @Test
    fun `syncIdeaCache does not overwrite existing idea dir`(@TempDir tmpDir: Path) {
        val cacheDir = tmpDir.resolve("cache")
        val projectDir = tmpDir.resolve("project")
        Files.createDirectories(cacheDir.resolve(".idea"))
        Files.writeString(cacheDir.resolve(".idea").resolve("cached.xml"), "cached")
        Files.createDirectories(projectDir.resolve(".idea"))
        Files.writeString(projectDir.resolve(".idea").resolve("uncached.xml"), "uncached")

        val sync = CacheSync(realFs(), terminal)
        sync.syncIdeaCache(cacheDir, projectDir, overwrite = false)

        // Existing .idea dir should be untouched
        assertTrue(Files.exists(projectDir.resolve(".idea").resolve("uncached.xml")))
    }

    @Test
    fun `syncIdeaCache no cache no problem`(@TempDir tmpDir: Path) {
        val cacheDir = tmpDir.resolve("nonexistent-cache")
        val projectDir = tmpDir.resolve("project")
        Files.createDirectories(projectDir.resolve(".idea"))
        Files.writeString(projectDir.resolve(".idea").resolve("uncached.xml"), "content")

        val sync = CacheSync(realFs(), terminal)
        sync.syncIdeaCache(cacheDir, projectDir)

        // Existing should still be there, nothing copied
        assertTrue(Files.exists(projectDir.resolve(".idea").resolve("uncached.xml")))
    }

    @Test
    fun `syncConfigCache copies jdk table from cache when not present`(@TempDir tmpDir: Path) {
        val confDir = tmpDir.resolve("conf")
        val cacheDir = tmpDir.resolve("cache")

        // Create cache file
        val cacheFile = cacheDir.resolve("config").resolve("253").resolve("jdk.table.xml")
        Files.createDirectories(cacheFile.parent)
        Files.writeString(cacheFile, "cached")

        // Create conf options dir
        Files.createDirectories(confDir.resolve("options"))

        val sync = CacheSync(realFs(), terminal)
        sync.syncConfigCache(confDir, cacheDir, "253", fromCache = true)

        val jdkTable = confDir.resolve("options").resolve("jdk.table.xml")
        assertTrue(Files.exists(jdkTable))
        assertEquals("cached", Files.readString(jdkTable))
    }

    @Test
    fun `syncConfigCache does not overwrite existing jdk table`(@TempDir tmpDir: Path) {
        val confDir = tmpDir.resolve("conf")
        val cacheDir = tmpDir.resolve("cache")

        val cacheFile = cacheDir.resolve("config").resolve("253").resolve("jdk.table.xml")
        Files.createDirectories(cacheFile.parent)
        Files.writeString(cacheFile, "cached")

        val jdkTable = confDir.resolve("options").resolve("jdk.table.xml")
        Files.createDirectories(jdkTable.parent)
        Files.writeString(jdkTable, "uncached")

        val sync = CacheSync(realFs(), terminal)
        sync.syncConfigCache(confDir, cacheDir, "253", fromCache = true)

        assertEquals("uncached", Files.readString(jdkTable))
    }

    @Test
    fun `syncConfigCache stores jdk table to cache`(@TempDir tmpDir: Path) {
        val confDir = tmpDir.resolve("conf")
        val cacheDir = tmpDir.resolve("cache")

        val jdkTable = confDir.resolve("options").resolve("jdk.table.xml")
        Files.createDirectories(jdkTable.parent)
        Files.writeString(jdkTable, "current config")

        val sync = CacheSync(realFs(), terminal)
        sync.syncConfigCache(confDir, cacheDir, "253", fromCache = false)

        val cacheFile = cacheDir.resolve("config").resolve("253").resolve("jdk.table.xml")
        assertTrue(Files.exists(cacheFile))
        assertEquals("current config", Files.readString(cacheFile))
    }

    @Test
    fun `writeFileIfNew creates file when not present`(@TempDir tmpDir: Path) {
        val filePath = tmpDir.resolve("new.txt")

        val sync = CacheSync(realFs(), terminal)
        sync.writeFileIfNew(filePath, "content")

        assertTrue(Files.exists(filePath))
        assertEquals("content", Files.readString(filePath))
    }

    @Test
    fun `writeFileIfNew does not overwrite existing file`(@TempDir tmpDir: Path) {
        val filePath = tmpDir.resolve("existing.txt")
        Files.writeString(filePath, "original")

        val sync = CacheSync(realFs(), terminal)
        sync.writeFileIfNew(filePath, "new content")

        assertEquals("original", Files.readString(filePath))
    }
}

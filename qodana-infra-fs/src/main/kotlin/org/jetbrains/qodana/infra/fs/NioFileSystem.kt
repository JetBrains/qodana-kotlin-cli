package org.jetbrains.qodana.infra.fs

import org.jetbrains.qodana.core.port.FileSystem
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.*

class NioFileSystem : FileSystem {

    override fun read(path: Path): String = path.readText()

    override fun readBytes(path: Path): ByteArray = path.readBytes()

    override fun write(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(content)
    }

    override fun writeBytes(path: Path, content: ByteArray) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeBytes(content)
    }

    override fun copy(source: Path, target: Path) {
        target.parent?.let { Files.createDirectories(it) }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun walk(root: Path, glob: String?): Sequence<Path> {
        if (!root.exists()) return emptySequence()
        return if (glob != null) {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
            Files.walk(root).use { stream ->
                stream.filter { matcher.matches(root.relativize(it)) }.toList().asSequence()
            }
        } else {
            Files.walk(root).use { stream ->
                stream.toList().asSequence()
            }
        }
    }

    override fun exists(path: Path): Boolean = path.exists()

    override fun createDirectories(path: Path): Path = Files.createDirectories(path)

    override fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

    override fun delete(path: Path) {
        if (path.isDirectory()) {
            path.toFile().deleteRecursively()
        } else {
            path.deleteIfExists()
        }
    }

    override fun extractArchive(archive: Path, target: Path) {
        Files.createDirectories(target)
        val name = archive.name.lowercase()
        when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTarGz(archive, target)
            name.endsWith(".zip") -> extractZip(archive, target)
            else -> throw IllegalArgumentException("Unsupported archive format: $name")
        }
    }

    private fun extractTarGz(archive: Path, target: Path) {
        BufferedInputStream(archive.inputStream()).use { bis ->
            GzipCompressorInputStream(bis).use { gzis ->
                TarArchiveInputStream(gzis).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val entryPath = target.resolve(entry.name).normalize()
                        if (!entryPath.startsWith(target)) {
                            throw SecurityException("Archive entry escapes target directory: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            Files.createDirectories(entryPath)
                        } else {
                            entryPath.parent?.let { Files.createDirectories(it) }
                            Files.copy(tar, entryPath, StandardCopyOption.REPLACE_EXISTING)
                            if (entry.mode and 0b001_000_000 != 0) {
                                entryPath.toFile().setExecutable(true)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private fun extractZip(archive: Path, target: Path) {
        ZipInputStream(archive.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = target.resolve(entry.name).normalize()
                if (!entryPath.startsWith(target)) {
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
}

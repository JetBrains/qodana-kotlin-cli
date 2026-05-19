package org.jetbrains.qodana.core.fs

import org.jetbrains.qodana.core.port.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

class NioFileSystem : FileSystem {
    override fun read(path: Path): String = path.readText()

    override fun readBytes(path: Path): ByteArray = path.readBytes()

    override fun write(
        path: Path,
        content: String,
    ) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(content)
    }

    override fun writeBytes(
        path: Path,
        content: ByteArray,
    ) {
        path.parent?.let { Files.createDirectories(it) }
        path.writeBytes(content)
    }

    override fun copy(
        source: Path,
        target: Path,
    ) {
        target.parent?.let { Files.createDirectories(it) }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun walk(
        root: Path,
        glob: String?,
    ): Sequence<Path> {
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

    /**
     * Extracts an archive using the system `tar` command, matching Go's extractArchive().
     * macOS `tar` handles .tar.gz, .zip, and .sit natively while preserving
     * file permissions and code signatures (Java's ZipInputStream corrupts them).
     *
     * Extracts to a temp dir first, then renames atomically — same as Go.
     */
    override fun extractArchive(
        archive: Path,
        target: Path,
    ) {
        val targetName =
            target.fileName?.toString()
                ?: throw IllegalArgumentException("Invalid target directory")

        val tempDir =
            Files.createTempDirectory("$targetName-").also {
                // Clean up temp dir on failure
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        it.toFile().deleteRecursively()
                    },
                )
            }

        try {
            val process =
                ProcessBuilder("tar", "-xf", archive.toString(), "-C", tempDir.toString())
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("tar extraction failed (exit $exitCode): $output")
            }

            // Remove existing target, then rename temp → target (atomic on same filesystem)
            if (target.exists()) {
                target.toFile().deleteRecursively()
            }
            Files.move(tempDir, target)
        } catch (e: Exception) {
            tempDir.toFile().deleteRecursively()
            throw e
        }
    }
}

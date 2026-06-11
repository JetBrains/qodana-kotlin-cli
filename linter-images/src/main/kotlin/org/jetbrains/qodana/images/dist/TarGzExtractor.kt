package org.jetbrains.qodana.images.dist

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream

/**
 * Production [Extractor] over commons-compress (no subprocess). Extracts the `.tar.gz` to a temp dir,
 * then flattens its single top-level directory so [targetDir] becomes the IDE root containing
 * `product-info.json` — mirrors `IdeInstaller.resolveInstallDir`. If the archive has no single
 * top-level directory, its contents are moved directly under [targetDir].
 *
 * Symlink entries are recreated as real symlinks and POSIX entry modes are applied (so the IDE's
 * launchers/JBR binaries stay executable), matching what a `tar -x` would produce on Linux.
 */
class TarGzExtractor : Extractor {
    private val posixSupported = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    override fun extractFlattened(
        archive: Path,
        targetDir: Path,
    ) {
        // Stage under the target's parent so the move into [targetDir] is a same-filesystem rename
        // (avoids cross-device EXDEV when temp and target live on different mounts).
        val stagingParent = targetDir.toAbsolutePath().parent ?: targetDir.toAbsolutePath()
        Files.createDirectories(stagingParent)
        val extractRoot = Files.createTempDirectory(stagingParent, "targz-extract")
        try {
            extractInto(archive, extractRoot)
            val source = singleTopLevelDir(extractRoot) ?: extractRoot
            moveContents(source, targetDir)
        } finally {
            extractRoot.toFile().deleteRecursively()
        }
    }

    private fun extractInto(
        archive: Path,
        destination: Path,
    ) {
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(Files.newInputStream(archive)))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val resolved = destination.resolve(entry.name).normalize()
                require(resolved.startsWith(destination)) { "Archive entry escapes target: ${entry.name}" }
                extractEntry(tar, entry, resolved)
                entry = tar.nextEntry
            }
        }
    }

    private fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        resolved: Path,
    ) {
        when {
            entry.isDirectory -> {
                Files.createDirectories(resolved)
                applyMode(resolved, entry.mode)
            }
            entry.isSymbolicLink -> {
                resolved.parent?.let { Files.createDirectories(it) }
                Files.deleteIfExists(resolved)
                Files.createSymbolicLink(resolved, resolved.fileSystem.getPath(entry.linkName))
            }
            else -> {
                resolved.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(resolved).use { tar.copyTo(it, BUFFER_SIZE) }
                applyMode(resolved, entry.mode)
            }
        }
    }

    /** Applies the tar entry's POSIX permission bits to [path] (no-op on non-POSIX filesystems). */
    private fun applyMode(
        path: Path,
        mode: Int,
    ) {
        if (!posixSupported) return
        val perms = PosixFilePermissions.fromString(rwxString(mode))
        Files.setPosixFilePermissions(path, perms)
    }

    /** Renders the low 9 bits of a tar mode as an `rwxr-xr-x`-style string for [PosixFilePermissions]. */
    private fun rwxString(mode: Int): String {
        val flags = "rwxrwxrwx"
        return buildString {
            for (i in 0 until PERMISSION_BITS) {
                val bit = 1 shl (PERMISSION_BITS - 1 - i)
                append(if (mode and bit != 0) flags[i] else '-')
            }
        }
    }

    /** The single child directory of [root] (and nothing else), or null if the layout is not a single wrapper dir. */
    private fun singleTopLevelDir(root: Path): Path? {
        Files.newDirectoryStream(root).use { stream ->
            val children = stream.toList()
            val only = children.singleOrNull() ?: return null
            return only.takeIf { Files.isDirectory(it) }
        }
    }

    private fun moveContents(
        source: Path,
        targetDir: Path,
    ) {
        Files.createDirectories(targetDir)
        Files.newDirectoryStream(source).use { stream ->
            for (child in stream) {
                Files.move(child, targetDir.resolve(source.relativize(child)))
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8192
        const val PERMISSION_BITS = 9
    }
}

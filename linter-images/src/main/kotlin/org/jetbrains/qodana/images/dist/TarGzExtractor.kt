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
            val stagedDirModes = extractInto(archive, extractRoot)
            val source = singleTopLevelDir(extractRoot) ?: extractRoot
            moveContents(source, targetDir)
            // Apply directory modes last, on the FINAL target paths: doing it here (rather than in
            // staging) keeps staged dirs writable for the flatten move and the cleanup below, and
            // ensures a non-writable mode (e.g. 0555) lands only after every child is in place.
            applyDirModes(stagedDirModes, source, targetDir)
        } finally {
            extractRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Extracts every entry of [archive] under [destination], returning the staged directory paths and
     * their tar modes so the caller can apply them after the contents are moved into the final target.
     */
    private fun extractInto(
        archive: Path,
        destination: Path,
    ): List<Pair<Path, Int>> {
        // Absolute + normalized base so the zip-slip guard (`startsWith(base)`) is robust regardless of
        // how [destination] was passed in, and so it can be reused to validate symlink targets.
        val base = destination.toAbsolutePath().normalize()
        val stagedDirModes = mutableListOf<Pair<Path, Int>>()
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(Files.newInputStream(archive)))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val resolved = base.resolve(entry.name).normalize()
                require(resolved.startsWith(base)) { "Archive entry escapes target: ${entry.name}" }
                extractEntry(tar, entry, resolved, base, stagedDirModes)
                entry = tar.nextEntry
            }
        }
        return stagedDirModes
    }

    /**
     * Applies the collected directory [modes] (captured at staging paths under [source]) to the
     * corresponding paths under [targetDir]. Directories outside [source] (e.g. a flattened wrapper)
     * are not moved into the target and are skipped. Modes are applied deepest-first so that a
     * restrictive parent mode (one that drops the traverse bit) cannot block setting a child's mode.
     */
    private fun applyDirModes(
        modes: List<Pair<Path, Int>>,
        source: Path,
        targetDir: Path,
    ) {
        val sourceBase = source.toAbsolutePath().normalize()
        modes
            .map { (stagedDir, mode) -> stagedDir.toAbsolutePath().normalize() to mode }
            .filter { (absStaged, _) -> absStaged != sourceBase && absStaged.startsWith(sourceBase) }
            .sortedByDescending { (absStaged, _) -> absStaged.nameCount }
            .forEach { (absStaged, mode) ->
                applyMode(targetDir.resolve(sourceBase.relativize(absStaged)), mode)
            }
    }

    private fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        resolved: Path,
        base: Path,
        stagedDirModes: MutableList<Pair<Path, Int>>,
    ) {
        when {
            entry.isDirectory -> {
                Files.createDirectories(resolved)
                stagedDirModes += resolved to entry.mode
            }
            entry.isSymbolicLink -> {
                resolved.parent?.let { Files.createDirectories(it) }
                val linkTarget = resolved.fileSystem.getPath(entry.linkName)
                val resolvedTarget = resolved.parent!!.resolve(linkTarget).normalize()
                require(resolvedTarget.startsWith(base)) {
                    "Archive symlink escapes target: ${entry.name} -> ${entry.linkName}"
                }
                Files.deleteIfExists(resolved)
                Files.createSymbolicLink(resolved, linkTarget)
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

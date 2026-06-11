package org.jetbrains.qodana.images.dist

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Production [Extractor] over commons-compress (no subprocess). Extracts the `.tar.gz` to a temp dir,
 * then flattens its single top-level directory so [targetDir] becomes the IDE root containing
 * `product-info.json` — mirrors `IdeInstaller.resolveInstallDir`. If the archive has no single
 * top-level directory, its contents are moved directly under [targetDir].
 */
class TarGzExtractor : Extractor {
    override fun extractFlattened(
        archive: Path,
        targetDir: Path,
    ) {
        val extractRoot = Files.createTempDirectory("targz-extract")
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
                if (entry.isDirectory) {
                    Files.createDirectories(resolved)
                } else {
                    resolved.parent?.let { Files.createDirectories(it) }
                    Files.newOutputStream(resolved).use { tar.copyTo(it, BUFFER_SIZE) }
                }
                entry = tar.nextEntry
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
    }
}

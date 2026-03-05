package org.jetbrains.qodana.engine.fs

import org.jetbrains.qodana.engine.port.WebUiProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

class WebUiExtractor : WebUiProvider {

    override fun extractWebUi(targetDir: Path) {
        val resource = javaClass.classLoader.getResourceAsStream("web-ui.zip")
            ?: throw IllegalStateException("web-ui.zip not found in classpath resources")

        Files.createDirectories(targetDir)

        ZipInputStream(resource).use { zis ->
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
}

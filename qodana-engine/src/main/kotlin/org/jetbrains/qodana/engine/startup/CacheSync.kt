package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Path

class CacheSync(
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
) {
    fun syncIdeaCache(cacheDir: Path, projectDir: Path, overwrite: Boolean = false) {
        val src = cacheDir.resolve(".idea")
        if (!fileSystem.exists(src)) {
            terminal.debug("Source .idea directory does not exist: $src")
            return
        }
        val dst = projectDir.resolve(".idea")
        if (!overwrite && fileSystem.exists(dst)) {
            terminal.debug(".idea directory already exists, skipping sync")
            return
        }
        terminal.debug("Sync IDE cache from: $src to: $dst")
        fileSystem.copy(src, dst)
    }

    fun syncConfigCache(
        confDirPath: Path,
        cacheDir: Path,
        versionBranch: String,
        fromCache: Boolean,
    ) {
        val jdkTableFile = confDirPath.resolve("options").resolve("jdk.table.xml")
        val cacheFile = cacheDir.resolve("config").resolve(versionBranch).resolve("jdk.table.xml")

        if (fromCache) {
            if (!fileSystem.exists(cacheFile)) return
            if (!fileSystem.exists(jdkTableFile)) {
                fileSystem.copy(cacheFile, jdkTableFile)
                terminal.debug("SDK table is synced from cache")
            }
        } else {
            if (!fileSystem.exists(jdkTableFile)) {
                terminal.debug("SDK table isn't stored to cache, file doesn't exist")
            } else {
                fileSystem.createDirectories(cacheFile.parent)
                fileSystem.copy(jdkTableFile, cacheFile)
                terminal.debug("SDK table is stored to cache")
            }
        }
    }

    fun writeFileIfNew(path: Path, content: String) {
        if (!fileSystem.exists(path)) {
            fileSystem.write(path, content)
        }
    }
}

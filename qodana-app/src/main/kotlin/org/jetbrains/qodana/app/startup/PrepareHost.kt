package org.jetbrains.qodana.app.startup

import org.jetbrains.qodana.core.model.ScanContext
import org.jetbrains.qodana.core.model.ScanPaths
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Path

class PrepareHost(
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
) {
    fun prepare(context: ScanContext): PreparedHost {
        ensureDirectories(context.paths)

        if (context.runtime.clearCache) {
            terminal.println("Clearing cache directory...")
            fileSystem.delete(context.paths.cacheDir)
            fileSystem.createDirectories(context.paths.cacheDir)
        }

        return PreparedHost(
            ideDir = context.runtime.ideDir,
            uploadToken = context.auth.token,
        )
    }

    private fun ensureDirectories(paths: ScanPaths) {
        fileSystem.createDirectories(paths.resultsDir)
        fileSystem.createDirectories(paths.reportDir)
        fileSystem.createDirectories(paths.cacheDir)
    }
}

data class PreparedHost(
    val ideDir: Path?,
    val uploadToken: String?,
)

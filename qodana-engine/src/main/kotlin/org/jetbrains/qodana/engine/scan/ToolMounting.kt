package org.jetbrains.qodana.engine.scan

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

object ToolMounting {
    fun getToolsMountPath(cacheDir: Path): Path {
        val toolsDir = cacheDir.resolve("tools")
        Files.createDirectories(toolsDir)
        return toolsDir
    }

    fun processAuxiliaryTool(
        name: String,
        subDir: String,
        cacheDir: Path,
        content: ByteArray,
    ): Path {
        val dir = cacheDir.resolve(subDir)
        Files.createDirectories(dir)
        val toolPath = dir.resolve(name)
        if (!toolPath.exists()) {
            toolPath.writeBytes(content)
        }
        return toolPath
    }

    fun isInDirectory(
        base: String,
        target: String,
    ): Boolean {
        val normalizedBase = if (base.endsWith("/")) base else "$base/"
        return target.startsWith(normalizedBase) || target == base
    }
}

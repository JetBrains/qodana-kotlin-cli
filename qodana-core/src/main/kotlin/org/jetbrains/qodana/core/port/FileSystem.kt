package org.jetbrains.qodana.core.port

import java.nio.file.Path

interface FileSystem {
    fun read(path: Path): String
    fun readBytes(path: Path): ByteArray
    fun write(path: Path, content: String)
    fun writeBytes(path: Path, content: ByteArray)
    fun copy(source: Path, target: Path)
    fun walk(root: Path, glob: String? = null): Sequence<Path>
    fun exists(path: Path): Boolean
    fun createDirectories(path: Path): Path
    fun tempDir(prefix: String): Path
    fun delete(path: Path)
    fun extractArchive(archive: Path, target: Path)
}

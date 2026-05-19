package org.jetbrains.qodana.engine.fs

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

object FileUtils {
    fun getSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getFileSha256(path: Path): Result<String> {
        if (!path.isRegularFile()) {
            return Result.failure(IllegalArgumentException("File does not exist: $path"))
        }
        return try {
            Files.newInputStream(path).use { Result.success(getSha256(it)) }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun copyDir(
        source: Path,
        target: Path,
    ) {
        if (!source.isDirectory()) throw IllegalArgumentException("Source is not a directory: $source")
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dest = target.resolve(source.relativize(src))
                if (src.isDirectory()) {
                    Files.createDirectories(dest)
                } else {
                    dest.parent?.let { Files.createDirectories(it) }
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

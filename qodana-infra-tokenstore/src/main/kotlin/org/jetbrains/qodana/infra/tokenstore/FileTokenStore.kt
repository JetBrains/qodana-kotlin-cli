package org.jetbrains.qodana.infra.tokenstore

import org.jetbrains.qodana.core.port.TokenStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileTokenStore(
    private val storageDir: Path = defaultStorageDir(),
) : TokenStore {

    override fun load(key: String): String? {
        val file = storageDir.resolve(key)
        return if (file.exists()) file.readText().trim().takeIf { it.isNotBlank() } else null
    }

    override fun save(key: String, value: String) {
        storageDir.createDirectories()
        val file = storageDir.resolve(key)
        file.writeText(value)
        // Set file permissions to owner-only on Unix-like systems
        file.toFile().apply {
            setReadable(false, false)
            setWritable(false, false)
            setReadable(true, true)
            setWritable(true, true)
        }
    }

    override fun delete(key: String) {
        storageDir.resolve(key).deleteIfExists()
    }

    companion object {
        fun defaultStorageDir(): Path {
            val home = System.getProperty("user.home")
            return Path.of(home, ".config", "qodana")
        }
    }
}

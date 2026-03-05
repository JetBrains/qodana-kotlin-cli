package org.jetbrains.qodana.core.port

interface TokenStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
    fun delete(key: String)
}

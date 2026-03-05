package org.jetbrains.qodana.infra.tokenstore

import org.jetbrains.qodana.core.port.TokenStore

class EnvTokenStore(
    private val getEnv: (String) -> String? = System::getenv,
) : TokenStore {

    override fun load(key: String): String? = getEnv(key)?.takeIf { it.isNotBlank() }

    override fun save(key: String, value: String) {
        // Environment variables cannot be persistently set from JVM
    }

    override fun delete(key: String) {
        // Environment variables cannot be deleted from JVM
    }
}

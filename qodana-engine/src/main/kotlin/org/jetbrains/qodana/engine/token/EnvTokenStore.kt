package org.jetbrains.qodana.engine.token

import org.jetbrains.qodana.engine.port.TokenStore

class EnvTokenStore(
    private val getEnv: (String) -> String? = System::getenv,
) : TokenStore {
    override fun load(key: String): String? = getEnv(key)?.takeIf { it.isNotBlank() }

    override fun save(
        key: String,
        value: String,
    ) {
        // Environment variables cannot be persistently set from JVM
    }

    override fun delete(key: String) {
        // Environment variables cannot be deleted from JVM
    }
}

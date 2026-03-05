package org.jetbrains.qodana.core.env

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QodanaEnvTest {

    @Test
    fun `TOKEN constant is QODANA_TOKEN`() {
        assertEquals("QODANA_TOKEN", QodanaEnv.TOKEN)
    }

    @Test
    fun `LICENSE_ONLY_TOKEN constant`() {
        assertEquals("QODANA_LICENSE_ONLY_TOKEN", QodanaEnv.LICENSE_ONLY_TOKEN)
    }

    @Test
    fun `ENDPOINT constant`() {
        assertEquals("QODANA_ENDPOINT", QodanaEnv.ENDPOINT)
    }

    @Test
    fun `DEFAULT_ENDPOINT is qodana cloud`() {
        assertEquals("https://qodana.cloud", QodanaEnv.DEFAULT_ENDPOINT)
    }

    @Test
    fun `NUGET env vars are set`() {
        assertEquals("QODANA_NUGET_URL", QodanaEnv.NUGET_URL)
        assertEquals("QODANA_NUGET_USER", QodanaEnv.NUGET_USER)
        assertEquals("QODANA_NUGET_PASSWORD", QodanaEnv.NUGET_PASSWORD)
        assertEquals("QODANA_NUGET_NAME", QodanaEnv.NUGET_NAME)
    }

    @Test
    fun `cloud request env vars`() {
        assertEquals("QODANA_CLOUD_REQUEST_COOLDOWN", QodanaEnv.CLOUD_REQUEST_COOLDOWN)
        assertEquals("QODANA_CLOUD_REQUEST_TIMEOUT", QodanaEnv.CLOUD_REQUEST_TIMEOUT)
        assertEquals("QODANA_CLOUD_REQUEST_RETRIES", QodanaEnv.CLOUD_REQUEST_RETRIES)
    }

    @Test
    fun `all env var constants except DEFAULT_ENDPOINT start with QODANA prefix or are known system vars`() {
        val knownSystemVars = setOf("ANDROID_SDK_ROOT", "GEM_HOME", "BUNDLE_APP_CONFIG")
        val knownNonEnvFields = setOf("DEFAULT_ENDPOINT")
        val fields = QodanaEnv::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }

        for (field in fields) {
            if (field.name in knownNonEnvFields) continue
            field.isAccessible = true
            val value = field.get(null) as String
            assertTrue(
                value.startsWith("QODANA_") || value in knownSystemVars,
                "Env var ${field.name} = $value should start with QODANA_ or be a known system var"
            )
        }
    }
}

package org.jetbrains.qodana.cdnet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NugetConfigTest {
    private fun env(values: Map<String, String?>): (String) -> String? = { key -> values[key] }

    @Test
    fun `isNeeded returns true when required vars are set`() {
        val getEnv = env(
            mapOf(
                "QODANA_DOCKER" to "true",
                "QODANA_NUGET_URL" to "https://nuget.example.com/v3/index.json",
                "QODANA_NUGET_USER" to "user",
                "QODANA_NUGET_PASSWORD" to "password",
            ),
        )

        assertTrue(NugetConfig.isNeeded(getEnv))
    }

    @Test
    fun `isNeeded returns false when any required var is missing`() {
        val cases = listOf(
            mapOf(
                "QODANA_DOCKER" to "",
                "QODANA_NUGET_URL" to "https://nuget.example.com",
                "QODANA_NUGET_USER" to "user",
                "QODANA_NUGET_PASSWORD" to "password",
            ),
            mapOf(
                "QODANA_DOCKER" to "true",
                "QODANA_NUGET_URL" to "",
                "QODANA_NUGET_USER" to "user",
                "QODANA_NUGET_PASSWORD" to "password",
            ),
            mapOf(
                "QODANA_DOCKER" to "true",
                "QODANA_NUGET_URL" to "https://nuget.example.com",
                "QODANA_NUGET_USER" to "   ",
                "QODANA_NUGET_PASSWORD" to "password",
            ),
            mapOf(
                "QODANA_DOCKER" to "true",
                "QODANA_NUGET_URL" to "https://nuget.example.com",
                "QODANA_NUGET_USER" to "user",
                "QODANA_NUGET_PASSWORD" to null,
            ),
        )

        cases.forEach { case ->
            assertFalse(NugetConfig.isNeeded(env(case)))
        }
    }

    @Test
    fun `prepare writes config with explicit source name`(@TempDir homeDir: Path) {
        val getEnv = env(
            mapOf(
                "QODANA_NUGET_NAME" to "qdn",
                "QODANA_NUGET_URL" to "test_url",
                "QODANA_NUGET_USER" to "test_user",
                "QODANA_NUGET_PASSWORD" to "test_password",
            ),
        )

        NugetConfig.prepare(homeDir, getEnv)

        val configPath = homeDir.resolve(".nuget/NuGet/NuGet.Config")
        assertTrue(Files.exists(configPath))

        val content = Files.readString(configPath).trim()
        val expected = """
            <?xml version="1.0" encoding="utf-8"?>
            <configuration>
              <packageSources>
                <clear />
                <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
                <add key="qdn" value="test_url" />
              </packageSources>
              <packageSourceCredentials>
                <qdn>
                  <add key="Username" value="test_user" />
                  <add key="ClearTextPassword" value="test_password" />
                </qdn>
              </packageSourceCredentials>
            </configuration>
        """.trimIndent()
        assertEquals(expected, content)
    }

    @Test
    fun `prepare uses default source name when name is blank`(@TempDir homeDir: Path) {
        val getEnv = env(
            mapOf(
                "QODANA_NUGET_NAME" to "   ",
                "QODANA_NUGET_URL" to "test_url",
                "QODANA_NUGET_USER" to "test_user",
                "QODANA_NUGET_PASSWORD" to "test_password",
            ),
        )

        NugetConfig.prepare(homeDir, getEnv)

        val content = Files.readString(homeDir.resolve(".nuget/NuGet/NuGet.Config"))
        assertTrue(content.contains("<add key=\"qodana\" value=\"test_url\" />"))
        assertTrue(content.contains("<qodana>"))
    }

    @Test
    fun `unsetVariables returns empty overrides for child process`() {
        val overrides = NugetConfig.unsetVariables()

        assertEquals(
            mapOf(
                "QODANA_NUGET_URL" to "",
                "QODANA_NUGET_USER" to "",
                "QODANA_NUGET_PASSWORD" to "",
                "QODANA_NUGET_NAME" to "",
            ),
            overrides,
        )
    }
}

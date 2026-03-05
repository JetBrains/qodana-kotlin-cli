package org.jetbrains.qodana.cdnet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NugetConfigTest {

    @Test
    fun `isNeeded returns false when env vars not set`() {
        val url = System.getenv("QODANA_NUGET_URL")
        if (url.isNullOrBlank()) {
            assertFalse(NugetConfig.isNeeded())
        }
    }

    @Test
    fun `prepare creates nuget directory structure`(@TempDir homeDir: Path) {
        NugetConfig.prepare(homeDir)

        val nugetDir = homeDir.resolve(".nuget/NuGet")
        assertTrue(Files.exists(nugetDir), "NuGet directory should be created")

        val configPath = nugetDir.resolve("NuGet.Config")
        assertTrue(Files.exists(configPath), "NuGet.Config should be created")

        val content = Files.readString(configPath)
        assertTrue(content.contains("nuget.org"), "Should always include nuget.org source")
        assertTrue(content.contains("packageSources"), "Should have package sources section")
    }

    @Test
    fun `prepare config includes default source name when QODANA_NUGET_NAME not set`(@TempDir homeDir: Path) {
        NugetConfig.prepare(homeDir)
        val content = Files.readString(homeDir.resolve(".nuget/NuGet/NuGet.Config"))
        assertTrue(content.contains("qodana"), "Default name should be 'qodana'")
    }

    @Test
    fun `unsetVariables does not throw`() {
        NugetConfig.unsetVariables()
    }
}

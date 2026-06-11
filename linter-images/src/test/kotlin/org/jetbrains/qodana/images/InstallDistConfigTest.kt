package org.jetbrains.qodana.images

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * Guards the `application`-plugin wiring the Docker builder stage relies on:
 * `:linter-images:installDist` must stage a launcher invoking
 * `org.jetbrains.qodana.images.MainKt`. Asserting on build.gradle.kts keeps this a
 * fast, Docker-free unit test.
 */
class InstallDistConfigTest {
    @Test
    fun `build script applies application plugin and pins MainKt`() {
        val buildScript = Path.of("build.gradle.kts").readText()
        assertTrue("application" in buildScript, "linter-images must apply the application plugin for installDist")
        assertTrue(
            "org.jetbrains.qodana.images.MainKt" in buildScript,
            "application mainClass must point at MainKt",
        )
    }
}

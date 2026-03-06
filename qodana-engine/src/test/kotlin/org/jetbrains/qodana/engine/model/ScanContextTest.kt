package org.jetbrains.qodana.engine.model

import org.jetbrains.qodana.core.model.ScanPaths
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanContextTest {

    private fun minimalContext() = ScanContext(
        paths = ScanPaths(
            projectDir = Path.of("/project"),
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        ),
        auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(),
        ci = CiContext(),
        report = ReportOptions(),
        docker = DockerOptions(),
    )

    @Test
    fun `default scan context values`() {
        val ctx = minimalContext()
        assertNull(ctx.linter)
        assertNull(ctx.profile)
        assertNull(ctx.yaml)
        assertEquals(RunScenario.Default, ctx.scenario)
        assertEquals(DockerLauncherExecutionProfile, ctx.executionProfile)
    }

    @Test
    fun `scan context with docker image`() {
        val ctx = minimalContext().copy(
            docker = DockerOptions(image = "jetbrains/qodana-jvm:2025.3"),
        )
        assertEquals("jetbrains/qodana-jvm:2025.3", ctx.docker.image)
    }

    @Test
    fun `scan context with auth token`() {
        val ctx = minimalContext().copy(
            auth = AuthContext(token = "test-token", endpoint = "https://qodana.cloud"),
        )
        assertTrue(ctx.auth.hasToken)
        assertFalse(ctx.auth.isLicenseOnly)
    }

    @Test
    fun `scan context with profile`() {
        val ctx = minimalContext().copy(
            profile = ProfileSpec(name = "qodana.starter"),
        )
        assertEquals("qodana.starter", ctx.profile?.name)
    }

    @Test
    fun `docker options skip pull`() {
        val opts = DockerOptions(image = "test:latest", skipPull = true)
        assertTrue(opts.skipPull)
    }

    @Test
    fun `docker options no docker pull`() {
        val opts = DockerOptions(image = "test:latest", noDockerPull = true)
        assertTrue(opts.noDockerPull)
    }

    @Test
    fun `docker options with volumes`() {
        val opts = DockerOptions(
            image = "test:latest",
            volumes = listOf("/host/path:/container/path", "/data:/data:ro"),
        )
        assertEquals(2, opts.volumes.size)
    }

    @Test
    fun `docker options with env vars`() {
        val opts = DockerOptions(
            image = "test:latest",
            envVars = mapOf("KEY" to "value"),
        )
        assertEquals("value", opts.envVars["KEY"])
    }

    @Test
    fun `docker options with resource limits`() {
        val opts = DockerOptions(
            image = "test:latest",
            memoryLimit = 4L * 1024 * 1024 * 1024,
            cpuLimit = 4,
        )
        assertEquals(4L * 1024 * 1024 * 1024, opts.memoryLimit)
        assertEquals(4, opts.cpuLimit)
    }
}

package org.jetbrains.qodana.engine.docker

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.engine.model.ContainerRunSpec
import org.jetbrains.qodana.engine.model.MountSpec
import org.jetbrains.qodana.engine.model.ResourceLimits
import org.jetbrains.qodana.engine.port.EngineType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for DockerJavaEngine against a real Docker daemon.
 *
 * `@Tag("docker")` routes the class through the `parityTest` Gradle task,
 * which is the only task that executes Docker-tagged tests. If Docker is
 * unreachable when the test runs, the suite fails loudly (per CLAUDE.md
 * "tests must never silently skip on missing dependencies").
 */
@Tag("docker")
class DockerJavaEngineIntegrationTest {
    companion object {
        private lateinit var engine: DockerJavaEngine
        private const val ALPINE_IMAGE = "alpine:3.20"

        @JvmStatic
        @BeforeAll
        fun checkDocker() {
            engine = DockerJavaEngine()
            try {
                // Blocking check — fail loudly if Docker is not running.
                kotlinx.coroutines.runBlocking { engine.info() }
            } catch (e: Exception) {
                fail("@Tag(\"docker\") test ran but Docker is unreachable: ${e.message}")
            }
        }
    }

    @Test
    fun `info returns Docker or Podman engine`() =
        runTest {
            val info = engine.info()
            assertNotNull(info.version)
            assertTrue(info.version.isNotBlank(), "Engine version should not be blank")
            assertTrue(
                info.engineType == EngineType.DOCKER || info.engineType == EngineType.PODMAN,
                "Engine type should be Docker or Podman",
            )
        }

    @Test
    fun `pull image succeeds`() =
        runTest(timeout = 2.minutes) {
            val messages = mutableListOf<String>()
            engine.pull(ALPINE_IMAGE) { messages.add(it) }
            assertTrue(messages.isNotEmpty(), "Should have received progress messages")
            assertTrue(engine.imageExists(ALPINE_IMAGE), "Image should exist after pull")
        }

    @Test
    fun `imageExists returns false for nonexistent image`() =
        runTest {
            assertFalse(engine.imageExists("nonexistent/image:999.999.999"))
        }

    @Test
    fun `create start logs wait remove lifecycle`() =
        runTest(timeout = 1.minutes) {
            // Pull first
            engine.pull(ALPINE_IMAGE) {}

            val spec =
                ContainerRunSpec(
                    image = ALPINE_IMAGE,
                    cmd = listOf("echo", "hello from docker test"),
                )

            val containerId = engine.create(spec)
            assertNotNull(containerId)
            assertTrue(containerId.isNotBlank())

            try {
                engine.start(containerId)

                val exitStatus = engine.wait(containerId)
                assertEquals(0, exitStatus.exitCode, "Container should exit with code 0")
                assertFalse(exitStatus.oomKilled, "Container should not be OOM killed")
            } finally {
                engine.remove(containerId, force = true)
            }
        }

    @Test
    fun `container logs are captured`() =
        runTest(timeout = 1.minutes) {
            engine.pull(ALPINE_IMAGE) {}

            val spec =
                ContainerRunSpec(
                    image = ALPINE_IMAGE,
                    cmd = listOf("sh", "-c", "echo stdout-line && echo stderr-line >&2"),
                )

            val containerId = engine.create(spec)
            try {
                engine.start(containerId)

                // Wait for container to finish
                engine.wait(containerId)

                // Collect logs after container has finished
                val logs = engine.logs(containerId).toList()
                val allText = logs.joinToString("\n") { it.text }

                assertTrue(allText.contains("stdout-line"), "Should capture stdout: got $allText")
                assertTrue(allText.contains("stderr-line"), "Should capture stderr: got $allText")
            } finally {
                engine.remove(containerId, force = true)
            }
        }

    @Test
    fun `container with non-zero exit code`() =
        runTest(timeout = 1.minutes) {
            engine.pull(ALPINE_IMAGE) {}

            val spec =
                ContainerRunSpec(
                    image = ALPINE_IMAGE,
                    cmd = listOf("sh", "-c", "exit 42"),
                )

            val containerId = engine.create(spec)
            try {
                engine.start(containerId)
                val exitStatus = engine.wait(containerId)
                assertEquals(42, exitStatus.exitCode, "Should capture non-zero exit code")
            } finally {
                engine.remove(containerId, force = true)
            }
        }

    @Test
    fun `container with volume mount`(
        @TempDir tempDir: Path,
    ) = runTest(timeout = 1.minutes) {
        engine.pull(ALPINE_IMAGE) {}

        // Write a file on the host
        val testFile = tempDir.resolve("input.txt")
        Files.writeString(testFile, "mount-test-content")

        val spec =
            ContainerRunSpec(
                image = ALPINE_IMAGE,
                mounts =
                    listOf(
                        MountSpec(
                            hostPath = tempDir.toString(),
                            containerPath = "/data",
                            readOnly = true,
                        ),
                    ),
                cmd = listOf("cat", "/data/input.txt"),
            )

        val containerId = engine.create(spec)
        try {
            engine.start(containerId)
            engine.wait(containerId)

            val logs = engine.logs(containerId).toList()
            val output = logs.joinToString("") { it.text }
            assertTrue(output.contains("mount-test-content"), "Should read mounted file: got $output")
        } finally {
            engine.remove(containerId, force = true)
        }
    }

    @Test
    fun `container with environment variables`() =
        runTest(timeout = 1.minutes) {
            engine.pull(ALPINE_IMAGE) {}

            val spec =
                ContainerRunSpec(
                    image = ALPINE_IMAGE,
                    env =
                        mapOf(
                            "TEST_VAR" to "hello-env",
                            "ANOTHER_VAR" to "world",
                        ),
                    cmd = listOf("sh", "-c", "echo \$TEST_VAR-\$ANOTHER_VAR"),
                )

            val containerId = engine.create(spec)
            try {
                engine.start(containerId)
                engine.wait(containerId)

                val logs = engine.logs(containerId).toList()
                val output = logs.joinToString("") { it.text }
                assertTrue(output.contains("hello-env-world"), "Should see env vars: got $output")
            } finally {
                engine.remove(containerId, force = true)
            }
        }

    @Test
    fun `container with resource limits`() =
        runTest(timeout = 1.minutes) {
            engine.pull(ALPINE_IMAGE) {}

            val spec =
                ContainerRunSpec(
                    image = ALPINE_IMAGE,
                    resources =
                        ResourceLimits(
                            memoryBytes = 64 * 1024 * 1024, // 64MB
                            cpuCount = 1,
                        ),
                    cmd = listOf("echo", "resource-limited"),
                )

            val containerId = engine.create(spec)
            try {
                engine.start(containerId)
                val exitStatus = engine.wait(containerId)
                assertEquals(0, exitStatus.exitCode)
            } finally {
                engine.remove(containerId, force = true)
            }
        }

    @Test
    fun `container with custom entrypoint`() =
        runTest(timeout = 1.minutes) {
            engine.pull(ALPINE_IMAGE) {}

            val spec =
                ContainerRunSpec(
                    image = ALPINE_IMAGE,
                    entrypoint = listOf("sh", "-c"),
                    cmd = listOf("echo custom-entrypoint-works"),
                )

            val containerId = engine.create(spec)
            try {
                engine.start(containerId)
                engine.wait(containerId)

                val logs = engine.logs(containerId).toList()
                val output = logs.joinToString("") { it.text }
                assertTrue(output.contains("custom-entrypoint-works"), "Should use custom entrypoint: got $output")
            } finally {
                engine.remove(containerId, force = true)
            }
        }

    @Test
    fun `container writes output file to mounted volume`(
        @TempDir tempDir: Path,
    ) = runTest(timeout = 1.minutes) {
        engine.pull(ALPINE_IMAGE) {}

        val outputDir = tempDir.resolve("output")
        Files.createDirectories(outputDir)

        val spec =
            ContainerRunSpec(
                image = ALPINE_IMAGE,
                mounts =
                    listOf(
                        MountSpec(
                            hostPath = outputDir.toString(),
                            containerPath = "/output",
                        ),
                    ),
                cmd = listOf("sh", "-c", "echo '{\"version\":\"2.1.0\"}' > /output/result.json"),
            )

        val containerId = engine.create(spec)
        try {
            engine.start(containerId)
            val exitStatus = engine.wait(containerId)
            assertEquals(0, exitStatus.exitCode)

            // Verify file was written to host
            val resultFile = outputDir.resolve("result.json")
            assertTrue(Files.exists(resultFile), "Container should write result.json to mounted volume")
            val content = Files.readString(resultFile)
            assertTrue(content.contains("2.1.0"), "Result file should contain version: got $content")
        } finally {
            engine.remove(containerId, force = true)
        }
    }
}

package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.product.Linters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainerImageUtilsTest {
    @Test
    fun `isUnofficialLinter detects unofficial images`() {
        assertTrue(ContainerImageUtils.isUnofficialLinter("hadolint"))
        assertTrue(ContainerImageUtils.isUnofficialLinter("myregistry/custom:latest"))
        assertFalse(ContainerImageUtils.isUnofficialLinter("jetbrains/qodana-jvm:latest"))
        assertFalse(ContainerImageUtils.isUnofficialLinter("jetbrains/qodana:latest"))
    }

    @Test
    fun `hasExactVersionTag checks version format`() {
        assertFalse(ContainerImageUtils.hasExactVersionTag("jetbrains/qodana"))
        assertFalse(ContainerImageUtils.hasExactVersionTag("jetbrains/qodana:latest"))
        assertTrue(ContainerImageUtils.hasExactVersionTag("jetbrains/qodana:2022.1"))
        assertTrue(ContainerImageUtils.hasExactVersionTag("jetbrains/qodana:${Linters.RELEASE_VERSION}"))
    }

    @Test
    fun `isCompatibleLinter checks version compatibility`() {
        assertFalse(ContainerImageUtils.isCompatibleLinter("hadolint"))
        assertFalse(ContainerImageUtils.isCompatibleLinter("jetbrains/qodana:latest"))
        assertFalse(ContainerImageUtils.isCompatibleLinter("jetbrains/qodana:2022.1"))
        assertTrue(ContainerImageUtils.isCompatibleLinter("jetbrains/qodana:${Linters.RELEASE_VERSION}"))
    }

    @Test
    fun `selectUser auto for non-privileged returns default user`() {
        val defaultUser = ContainerImageUtils.getDefaultUser()
        assertEquals(defaultUser, ContainerImageUtils.selectUser("jetbrains/qodana-cpp:2025.2-eap-clang18", "auto"))
    }

    @Test
    fun `selectUser auto for privileged returns empty`() {
        assertEquals("", ContainerImageUtils.selectUser("jetbrains/qodana-cpp:2025.2-eap-clang18-privileged", "auto"))
    }

    @Test
    fun `selectUser explicit UID preserved`() {
        assertEquals("0", ContainerImageUtils.selectUser("jetbrains/qodana-cpp:2025.2-eap", "0"))
        assertEquals("1337", ContainerImageUtils.selectUser("jetbrains/qodana-cpp:2025.2-eap", "1337"))
        assertEquals("0", ContainerImageUtils.selectUser("jetbrains/qodana-cpp:2025.2-eap-privileged", "0"))
    }

    @Test
    fun `isDockerUnauthorizedError detects auth errors`() {
        assertTrue(ContainerImageUtils.isDockerUnauthorizedError("unauthorized: authentication required"))
        assertTrue(ContainerImageUtils.isDockerUnauthorizedError("Unauthorized access"))
        assertTrue(ContainerImageUtils.isDockerUnauthorizedError("access denied"))
        assertTrue(ContainerImageUtils.isDockerUnauthorizedError("DENIED: permission denied"))
        assertTrue(ContainerImageUtils.isDockerUnauthorizedError("forbidden: access is forbidden"))
        assertTrue(ContainerImageUtils.isDockerUnauthorizedError("Forbidden"))
        assertFalse(ContainerImageUtils.isDockerUnauthorizedError("image not found"))
        assertFalse(ContainerImageUtils.isDockerUnauthorizedError("connection refused"))
        assertFalse(ContainerImageUtils.isDockerUnauthorizedError(""))
    }

    @Test
    fun `extractDockerVolumes parses unix volumes`() {
        val (source, target) = ContainerImageUtils.extractDockerVolumes("/host/path:/container/path")
        // On non-Windows, this should parse correctly
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            assertEquals("/host/path", source)
            assertEquals("/container/path", target)
        }
    }

    @Test
    fun `extractDockerVolumes handles empty`() {
        val (source, target) = ContainerImageUtils.extractDockerVolumes("")
        assertEquals("", source)
        assertEquals("", target)
    }

    @Test
    fun `extractDockerVolumes handles missing target`() {
        val (source, target) = ContainerImageUtils.extractDockerVolumes("/host/path")
        assertEquals("", source)
        assertEquals("", target)
    }

    @Test
    fun `generateDebugDockerRunCommand basic`() {
        val cmd =
            ContainerImageUtils.generateDebugDockerRunCommand(
                image = "jetbrains/qodana-jvm:latest",
                cmd = listOf("--analyze"),
            )
        assertTrue(cmd.contains("docker run"))
        assertTrue(cmd.contains("jetbrains/qodana-jvm:latest"))
        assertTrue(cmd.contains("--analyze"))
    }

    @Test
    fun `generateDebugDockerRunCommand with user`() {
        val cmd =
            ContainerImageUtils.generateDebugDockerRunCommand(
                image = "jetbrains/qodana-jvm:latest",
                user = "1000:1000",
            )
        assertTrue(cmd.contains("-u 1000:1000"))
    }

    @Test
    fun `generateDebugDockerRunCommand with env`() {
        val cmd =
            ContainerImageUtils.generateDebugDockerRunCommand(
                image = "jetbrains/qodana-jvm:latest",
                env = listOf("MY_VAR=value", "ANOTHER=test"),
            )
        assertTrue(cmd.contains("-e MY_VAR=value"))
        assertTrue(cmd.contains("-e ANOTHER=test"))
    }

    @Test
    fun `generateDebugDockerRunCommand filters token env`() {
        val cmd =
            ContainerImageUtils.generateDebugDockerRunCommand(
                image = "jetbrains/qodana-jvm:latest",
                env = listOf("SAFE_VAR=value", "QODANA_TOKEN=secret_token"),
            )
        assertTrue(cmd.contains("-e SAFE_VAR=value"))
        assertFalse(cmd.contains("secret_token"))
    }

    @Test
    fun `generateDebugDockerRunCommand with mounts`() {
        val cmd =
            ContainerImageUtils.generateDebugDockerRunCommand(
                image = "jetbrains/qodana-jvm:latest",
                mounts = listOf("/host/path" to "/container/path"),
            )
        assertTrue(cmd.contains("-v /host/path:/container/path"))
    }

    @Test
    fun `generateDebugDockerRunCommand with capabilities`() {
        val cmd =
            ContainerImageUtils.generateDebugDockerRunCommand(
                image = "jetbrains/qodana-jvm:latest",
                capAdd = listOf("SYS_PTRACE"),
            )
        assertTrue(cmd.contains("--cap-add SYS_PTRACE"))
    }

    @Test
    fun `generateDebugDockerRunCommand with rm and tty`() {
        val cmd =
            ContainerImageUtils.generateDebugDockerRunCommand(
                image = "jetbrains/qodana-jvm:latest",
                autoRemove = true,
                tty = true,
                attachStdout = true,
                attachStderr = true,
            )
        assertTrue(cmd.contains("--rm"))
        assertTrue(cmd.contains("-it"))
        assertTrue(cmd.contains("-a stdout"))
        assertTrue(cmd.contains("-a stderr"))
    }
}

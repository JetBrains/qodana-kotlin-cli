package org.jetbrains.qodana.engine.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerUtilsTest {

    // --- selectUser ---

    @Test
    fun `selectUser explicit user returned as-is`() {
        assertEquals("1000:1000", ContainerUtils.selectUser("jetbrains/qodana-jvm:latest", "1000:1000"))
    }

    @Test
    fun `selectUser empty user returned as-is`() {
        assertEquals("", ContainerUtils.selectUser("jetbrains/qodana-jvm:latest", ""))
    }

    @Test
    fun `selectUser auto with non-privileged image returns default`() {
        val result = ContainerUtils.selectUser("jetbrains/qodana-jvm:latest", "auto", defaultUser = "1000:1000")
        assertEquals("1000:1000", result)
    }

    @Test
    fun `selectUser auto with privileged image returns empty`() {
        val result = ContainerUtils.selectUser("jetbrains/qodana-jvm-privileged:latest", "auto", defaultUser = "1000:1000")
        assertEquals("", result)
    }

    @Test
    fun `selectUser auto with registry privileged image`() {
        val result = ContainerUtils.selectUser("registry.jetbrains.team/p/sa/containers/qodana-jvm-privileged:latest", "auto", defaultUser = "1000:1000")
        assertEquals("", result)
    }

    // --- encodeAuthToBase64 ---

    @Test
    fun `encodeAuthToBase64 basic auth`() {
        val encoded = ContainerUtils.encodeAuthToBase64("user", "pass")
        val decoded = String(java.util.Base64.getUrlDecoder().decode(encoded))
        assertTrue(decoded.contains("\"username\":\"user\""))
        assertTrue(decoded.contains("\"password\":\"pass\""))
    }

    @Test
    fun `encodeAuthToBase64 with server`() {
        val encoded = ContainerUtils.encodeAuthToBase64("user", "pass", "https://registry.example.com")
        val decoded = String(java.util.Base64.getUrlDecoder().decode(encoded))
        assertTrue(decoded.contains("\"serveraddress\":\"https://registry.example.com\""))
    }

    @Test
    fun `encodeAuthToBase64 empty auth`() {
        val encoded = ContainerUtils.encodeAuthToBase64("", "")
        val decoded = String(java.util.Base64.getUrlDecoder().decode(encoded))
        assertTrue(decoded.contains("\"username\":\"\""))
    }

    // --- extractDockerVolumes (Unix) ---

    @Test
    fun `extractDockerVolumes simple unix`() {
        val (source, target) = ContainerUtils.extractDockerVolumes("/host/path:/container/path")
        assertEquals("/host/path", source)
        assertEquals("/container/path", target)
    }

    @Test
    fun `extractDockerVolumes with options`() {
        val (source, target) = ContainerUtils.extractDockerVolumes("/host:/container:ro")
        assertEquals("/host", source)
        assertEquals("/container", target)
    }

    @Test
    fun `extractDockerVolumes no colon`() {
        val (source, target) = ContainerUtils.extractDockerVolumes("/only/path")
        assertEquals("", source)
        assertEquals("", target)
    }

    // --- isDockerUnauthorizedError ---

    @Test
    fun `isDockerUnauthorizedError unauthorized`() {
        assertTrue(ContainerUtils.isDockerUnauthorizedError("Error: unauthorized access"))
    }

    @Test
    fun `isDockerUnauthorizedError denied`() {
        assertTrue(ContainerUtils.isDockerUnauthorizedError("access denied"))
    }

    @Test
    fun `isDockerUnauthorizedError forbidden`() {
        assertTrue(ContainerUtils.isDockerUnauthorizedError("403 Forbidden"))
    }

    @Test
    fun `isDockerUnauthorizedError case insensitive`() {
        assertTrue(ContainerUtils.isDockerUnauthorizedError("UNAUTHORIZED"))
    }

    @Test
    fun `isDockerUnauthorizedError not auth error`() {
        assertFalse(ContainerUtils.isDockerUnauthorizedError("network timeout"))
    }

    @Test
    fun `isDockerUnauthorizedError empty`() {
        assertFalse(ContainerUtils.isDockerUnauthorizedError(""))
    }

    // --- image checks ---

    @Test
    fun `isUnofficialLinter true for hadolint`() {
        assertTrue(ContainerUtils.isUnofficialLinter("hadolint/hadolint:latest"))
    }

    @Test
    fun `isUnofficialLinter false for jetbrains`() {
        assertFalse(ContainerUtils.isUnofficialLinter("jetbrains/qodana-jvm:2025.3"))
    }

    @Test
    fun `hasExactVersionTag true`() {
        assertTrue(ContainerUtils.hasExactVersionTag("jetbrains/qodana-jvm:2025.3"))
    }

    @Test
    fun `hasExactVersionTag false for latest`() {
        assertFalse(ContainerUtils.hasExactVersionTag("jetbrains/qodana-jvm:latest"))
    }

    @Test
    fun `hasExactVersionTag false for no tag`() {
        assertFalse(ContainerUtils.hasExactVersionTag("jetbrains/qodana-jvm"))
    }

    @Test
    fun `isCompatibleLinter true`() {
        assertTrue(ContainerUtils.isCompatibleLinter("jetbrains/qodana-jvm:2025.3"))
    }

    @Test
    fun `isCompatibleLinter false for old version`() {
        assertFalse(ContainerUtils.isCompatibleLinter("jetbrains/qodana-jvm:2024.1"))
    }

    @Test
    fun `checkImage returns null for dev version`() {
        assertNull(ContainerUtils.checkImage("any", "dev"))
    }

    @Test
    fun `checkImage returns null for nightly version`() {
        assertNull(ContainerUtils.checkImage("any", "1.0.0-nightly"))
    }

    @Test
    fun `checkImage returns result for normal version`() {
        val result = ContainerUtils.checkImage("jetbrains/qodana-jvm:2025.3")
        assertNotNull(result)
        assertFalse(result.isUnofficial)
        assertTrue(result.hasExactVersion)
        assertTrue(result.isCompatible)
    }

    // --- generateDebugDockerRunCommand ---

    @Test
    fun `generateDebugDockerRunCommand basic`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand("jetbrains/qodana-jvm:latest")
        assertEquals("docker run --rm -a stdout -a stderr jetbrains/qodana-jvm:latest", cmd)
    }

    @Test
    fun `generateDebugDockerRunCommand with user`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand("img", user = "1000:1000")
        assertTrue(cmd.contains("-u 1000:1000"))
    }

    @Test
    fun `generateDebugDockerRunCommand with env`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand("img", envVars = listOf("FOO=bar"))
        assertTrue(cmd.contains("-e FOO=bar"))
    }

    @Test
    fun `generateDebugDockerRunCommand with volumes`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand("img", volumes = listOf("/a:/b"))
        assertTrue(cmd.contains("-v /a:/b"))
    }

    @Test
    fun `generateDebugDockerRunCommand with capabilities`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand("img", capabilities = listOf("SYS_PTRACE"))
        assertTrue(cmd.contains("--cap-add SYS_PTRACE"))
    }

    @Test
    fun `generateDebugDockerRunCommand with tty`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand("img", tty = true)
        assertTrue(cmd.contains("-it"))
    }

    @Test
    fun `generateDebugDockerRunCommand filters QODANA_TOKEN`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand(
            "img",
            envVars = listOf("QODANA_TOKEN=secret", "OTHER=keep"),
        )
        assertFalse(cmd.contains("QODANA_TOKEN"))
        assertTrue(cmd.contains("OTHER=keep"))
    }

    @Test
    fun `generateDebugDockerRunCommand keeps license token vars`() {
        val cmd = ContainerUtils.generateDebugDockerRunCommand(
            "img",
            envVars = listOf("QODANA_TOKEN=x", "QodanaLicenseOnlyToken=y"),
        )
        assertFalse(cmd.contains("QODANA_TOKEN=x"))
        assertTrue(cmd.contains("QodanaLicenseOnlyToken=y"))
    }
}

package org.jetbrains.qodana.core.model

import kotlin.test.*

class QodanaErrorTest {

    @Test
    fun `Network error message contains URL and cause`() {
        val error = QodanaError.Network(url = "https://api.example.com", cause = "timeout")
        assertContains(error.message, "https://api.example.com")
        assertContains(error.message, "timeout")
    }

    @Test
    fun `Auth error message contains reason`() {
        val error = QodanaError.Auth(reason = "invalid token")
        assertContains(error.message, "invalid token")
    }

    @Test
    fun `ToolMissing message contains tool and platform`() {
        val error = QodanaError.ToolMissing(tool = "docker", platform = "linux-arm64")
        assertContains(error.message, "docker")
        assertContains(error.message, "linux-arm64")
    }

    @Test
    fun `ProcessFailed message contains command, exit code, and stderr`() {
        val error = QodanaError.ProcessFailed(
            command = "qodana scan",
            exitCode = 1,
            stderr = "out of memory",
        )
        assertContains(error.message, "qodana scan")
        assertContains(error.message, "1")
        assertContains(error.message, "out of memory")
    }

    @Test
    fun `all variants implement QodanaError sealed interface`() {
        val errors: List<QodanaError> = listOf(
            QodanaError.Network(url = "url", cause = "cause"),
            QodanaError.Auth(reason = "reason"),
            QodanaError.Docker(reason = "reason"),
            QodanaError.ToolMissing(tool = "tool", platform = "platform"),
            QodanaError.InvalidConfig(path = "/path", reason = "reason"),
            QodanaError.ReportProcessing(reason = "reason"),
            QodanaError.ProcessFailed(command = "cmd", exitCode = 1, stderr = "err"),
            QodanaError.LinterError(linter = "linter", reason = "reason"),
        )
        for (error in errors) {
            assertIs<QodanaError>(error)
            assertTrue(error.message.isNotBlank(), "Message should not be blank for ${error::class.simpleName}")
        }
    }
}

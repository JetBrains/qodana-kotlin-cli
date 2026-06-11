package org.jetbrains.qodana.images.cli

import java.nio.file.Path
import kotlin.test.assertEquals
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.Test

class Sha256ToolTest {
    @Test
    fun `computes sha256 via sha256sum first token`() {
        val runner = FakeCommandRunner()
        // Canonical FakeCommandRunner API: on(predicate, handler) — first match wins.
        runner.on({ it == listOf("sha256sum", "/tmp/a.tar.gz") }) {
            CommandResult(exitCode = 0, stdout = "deadbeef  /tmp/a.tar.gz\n", stderr = "")
        }
        val tool = Sha256Tool(runner)

        assertEquals("deadbeef", tool.sha256(Path.of("/tmp/a.tar.gz")))
        assertEquals(listOf(listOf("sha256sum", "/tmp/a.tar.gz")), runner.invocations)
    }
}

package org.jetbrains.qodana.images.process

import kotlin.test.Test
import kotlin.test.assertEquals

class FakeCommandRunnerTest {
    @Test
    fun `returns the registered result for a matching command and records the invocation`() {
        val fake = FakeCommandRunner()
        fake.on({ it.firstOrNull() == "gpg" }, CommandResult(0, "VALIDSIG", ""))

        val result = fake.run(listOf("gpg", "--verify", "a.asc", "a"))

        assertEquals(0, result.exitCode)
        assertEquals("VALIDSIG", result.stdout)
        assertEquals(listOf(listOf("gpg", "--verify", "a.asc", "a")), fake.invocations)
    }

    @Test
    fun `handler overload sees the argv and computes the result`() {
        val fake = FakeCommandRunner()
        fake.on({ it.firstOrNull() == "echo" }) { argv -> CommandResult(0, argv.last(), "") }

        assertEquals("hello", fake.run(listOf("echo", "hello")).stdout)
    }

    @Test
    fun `later matchers do not shadow earlier ones`() {
        val fake = FakeCommandRunner()
        fake.on({ it.contains("curl") }, CommandResult(0, "first", ""))
        fake.on({ it.contains("curl") }, CommandResult(0, "second", ""))

        assertEquals("first", fake.run(listOf("curl", "-fsSL", "x")).stdout)
    }

    @Test
    fun `unmatched command falls through to the default (success, empty)`() {
        val fake = FakeCommandRunner()
        // No rule registered → the canonical default fires (exit 0, empty output); it does NOT throw.
        val result = fake.run(listOf("unknown"))
        assertEquals(0, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals(listOf(listOf("unknown")), fake.invocations)

        // A test may override the fallback to model an environment where the default is failure.
        fake.default = { CommandResult(127, "", "command not found") }
        assertEquals(127, fake.run(listOf("missing")).exitCode)
    }
}

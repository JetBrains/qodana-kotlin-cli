package org.jetbrains.qodana.images.process

import java.nio.file.Path

/**
 * Test double for [CommandRunner] — the ONE API every image-tool unit test uses.
 * Register rules with [on] (predicate + a fixed [CommandResult] OR a handler that gets the argv);
 * the FIRST matching rule wins. An unmatched command falls through to [default] (success/empty
 * unless a test overrides it) — it does NOT throw. Every call is appended to [invocations] as the
 * raw argument list so tests can assert exact commands (e.g. the gpg isolated-keyring flags).
 */
class FakeCommandRunner : CommandRunner {
    val invocations = mutableListOf<List<String>>()
    private val rules = mutableListOf<Pair<(List<String>) -> Boolean, (List<String>) -> CommandResult>>()
    var default: (List<String>) -> CommandResult = { CommandResult(0, "", "") }

    fun on(
        matches: (List<String>) -> Boolean,
        result: CommandResult,
    ) = apply { rules += matches to { _ -> result } }

    fun on(
        matches: (List<String>) -> Boolean,
        handler: (List<String>) -> CommandResult,
    ) = apply { rules += matches to handler }

    override fun run(
        command: List<String>,
        workDir: Path?,
    ): CommandResult {
        invocations += command
        return (rules.firstOrNull { it.first(command) }?.second ?: default)(command)
    }
}

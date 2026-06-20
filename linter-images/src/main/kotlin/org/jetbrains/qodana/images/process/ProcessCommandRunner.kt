package org.jetbrains.qodana.images.process

import java.io.Reader
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Real [CommandRunner] backed by [ProcessBuilder]. stdout and stderr are drained on separate
 * threads so a child writing heavily to both streams cannot deadlock on a full pipe buffer.
 *
 * [timeout] is a hard failure-bound for a child that might never exit — some linters (notably CLion's
 * C/C++ analysis on an unresolvable project model) HANG rather than fail, which would otherwise block CI
 * for the whole job budget. A simple project completes in minutes on the slowest runner, so on expiry the
 * process is killed and a non-zero [CommandResult] with the cause on stderr is returned, surfaced as a
 * normal scan failure. Git calls through the same runner finish well under this bound, so it never fires there.
 */
class ProcessCommandRunner(
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : CommandRunner {
    override fun run(
        command: List<String>,
        workDir: Path?,
    ): CommandResult {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .apply { workDir?.let { directory(it.toFile()) } }
                .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        // Daemon threads: a reader that somehow never reaches EOF must never keep the JVM alive at shutdown.
        val stdoutThread = Thread { drain(process.inputStream.reader(), stdout) }.apply { isDaemon = true }
        val stderrThread = Thread { drain(process.errorStream.reader(), stderr) }.apply { isDaemon = true }
        stdoutThread.start()
        stderrThread.start()

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            // Kill the whole tree, not just the direct child: a surviving grandchild that inherited the
            // stdout/stderr pipe would keep its write-end open, and the drain-thread joins below would block
            // until that grandchild exits — re-introducing the unbounded wait this timeout exists to prevent.
            // (For the real callers — `docker run`, `git` — the direct child is the sole pipe holder, so the
            // descendants set is empty and this is belt-and-suspenders; the kill closes the pipe regardless.)
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor()
            stdoutThread.join()
            stderrThread.join()
            stderr.append(
                "\n[harness] command timed out after ${timeout.toMinutes()} min and was killed " +
                    "(a linter that hangs instead of failing — see QD-15107).",
            )
            return CommandResult(exitCode = TIMEOUT_EXIT_CODE, stdout = stdout.toString(), stderr = stderr.toString())
        }

        val exitCode = process.exitValue()
        // Join after exit: the child has exited, so both readers reach EOF.
        stdoutThread.join()
        stderrThread.join()

        return CommandResult(exitCode = exitCode, stdout = stdout.toString(), stderr = stderr.toString())
    }

    private fun drain(
        reader: Reader,
        sink: StringBuilder,
    ) {
        reader.use {
            val buffer = CharArray(BUFFER_SIZE)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                sink.append(buffer, 0, read)
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8192

        // The slowest legitimate scan (Gradle/Maven import for the JVM/Android build-project fixtures) finishes
        // well inside this on the slowest CI runner; a hung linter (cpp on a broken project model) does not.
        val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(15)

        // Conventional `timeout(1)` exit status, so a hung-and-killed scan reads as a normal non-zero failure.
        const val TIMEOUT_EXIT_CODE = 124
    }
}

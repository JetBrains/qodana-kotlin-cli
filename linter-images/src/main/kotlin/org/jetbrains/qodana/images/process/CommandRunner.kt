package org.jetbrains.qodana.images.process

import java.nio.file.Path

/** Result of running an external command. */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Port for running external commands (gpg, curl, sha256sum, tar, …).
 * All subprocess I/O in image-tool goes through this so unit tests use [FakeCommandRunner].
 */
interface CommandRunner {
    /**
     * Runs [command] and returns its captured output. Implementations MUST drain stdout and
     * stderr concurrently to avoid pipe-buffer deadlock on large output.
     */
    fun run(
        command: List<String>,
        workDir: Path? = null,
    ): CommandResult
}

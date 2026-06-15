package org.jetbrains.qodana.images.process

import java.io.Reader
import java.nio.file.Path

/**
 * Real [CommandRunner] backed by [ProcessBuilder]. stdout and stderr are drained on separate
 * threads so a child writing heavily to both streams cannot deadlock on a full pipe buffer.
 */
class ProcessCommandRunner : CommandRunner {
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
        val stdoutThread = Thread { drain(process.inputStream.reader(), stdout) }
        val stderrThread = Thread { drain(process.errorStream.reader(), stderr) }
        stdoutThread.start()
        stderrThread.start()

        val exitCode = process.waitFor()
        // Join unconditionally (no timeout): the child has exited, so both readers reach EOF.
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
    }
}

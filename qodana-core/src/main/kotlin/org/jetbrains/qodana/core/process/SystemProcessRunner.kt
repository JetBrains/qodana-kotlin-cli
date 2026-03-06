package org.jetbrains.qodana.core.process

import org.jetbrains.qodana.core.model.LogEvent
import org.jetbrains.qodana.core.model.LogSource
import org.jetbrains.qodana.core.model.ProcessResult
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.model.Stream
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.RunningProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Reader
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class SystemProcessRunner : ProcessRunner {
    companion object {
        // Matches Go CLI timeout placeholder behavior.
        private const val TIMEOUT_EXIT_CODE_PLACEHOLDER = 1000
    }

    override suspend fun run(spec: ProcessSpec): ProcessResult = withContext(Dispatchers.IO) {
        val process = buildProcess(spec)
            .redirectErrorStream(false)
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val stdoutReader = process.inputStream.reader()
        val stderrReader = process.errorStream.reader()

        val stdoutThread = Thread { readAll(stdoutReader, stdout) }
        val stderrThread = Thread { readAll(stderrReader, stderr) }
        stdoutThread.start()
        stderrThread.start()

        val timeout = spec.timeout
        val exitCode = if (timeout != null) {
            val completed = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                TIMEOUT_EXIT_CODE_PLACEHOLDER
            } else {
                process.exitValue()
            }
        } else {
            process.waitFor()
        }

        stdoutThread.join(5000)
        stderrThread.join(5000)

        ProcessResult(
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
        )
    }

    override suspend fun start(spec: ProcessSpec): RunningProcess {
        val process = withContext(Dispatchers.IO) {
            buildProcess(spec)
                .redirectErrorStream(false)
                .start()
        }
        return SystemRunningProcess(process, spec.timeout)
    }

    private fun buildProcess(spec: ProcessSpec): ProcessBuilder {
        val command = buildList {
            add(spec.command)
            addAll(spec.args)
        }
        return ProcessBuilder(command).apply {
            spec.workDir?.let { directory(it.toFile()) }
            if (spec.env.isNotEmpty()) {
                environment().putAll(spec.env)
            }
        }
    }
}

private class SystemRunningProcess(
    private val process: Process,
    private val timeout: Duration?,
) : RunningProcess {
    companion object {
        private const val TIMEOUT_EXIT_CODE_PLACEHOLDER = 1000
        private const val BUFFER_SIZE = 4096
    }

    override fun events(): Flow<LogEvent> = channelFlow {
        launch(Dispatchers.IO) {
            streamChunks(process.inputStream.reader()) { chunk ->
                send(LogEvent(LogSource.PROCESS, Stream.STDOUT, chunk, Instant.now()))
            }
        }

        launch(Dispatchers.IO) {
            streamChunks(process.errorStream.reader()) { chunk ->
                send(LogEvent(LogSource.PROCESS, Stream.STDERR, chunk, Instant.now()))
            }
        }
    }

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        if (timeout == null) {
            process.waitFor()
        } else {
            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (finished) {
                process.exitValue()
            } else {
                terminate()
                TIMEOUT_EXIT_CODE_PLACEHOLDER
            }
        }
    }

    override fun terminate() {
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }

    private suspend fun streamChunks(
        reader: Reader,
        sink: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        reader.use {
            val buffer = CharArray(BUFFER_SIZE)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) {
                    break
                }
                if (read == 0) {
                    continue
                }
                sink(String(buffer, 0, read))
            }
        }
    }
}

private fun readAll(reader: Reader, output: StringBuilder) {
    reader.use {
        val buffer = CharArray(4096)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            output.append(buffer, 0, read)
        }
    }
}

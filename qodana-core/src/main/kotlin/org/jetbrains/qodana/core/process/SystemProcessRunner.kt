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
import java.io.BufferedReader
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

        val stdoutReader = process.inputStream.bufferedReader()
        val stderrReader = process.errorStream.bufferedReader()

        val stdoutThread = Thread { stdoutReader.forEachLine { stdout.appendLine(it) } }
        val stderrThread = Thread { stderrReader.forEachLine { stderr.appendLine(it) } }
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
            stdout = stdout.toString().trimEnd(),
            stderr = stderr.toString().trimEnd(),
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
    }

    override fun events(): Flow<LogEvent> = channelFlow {
        val now = Instant.now()

        launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    send(LogEvent(LogSource.PROCESS, Stream.STDOUT, line, now))
                }
            }
        }

        launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    send(LogEvent(LogSource.PROCESS, Stream.STDERR, line, now))
                }
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
}

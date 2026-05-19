package org.jetbrains.qodana.core.port

import kotlinx.coroutines.flow.Flow
import org.jetbrains.qodana.core.model.LogEvent
import org.jetbrains.qodana.core.model.ProcessResult
import org.jetbrains.qodana.core.model.ProcessSpec

interface ProcessRunner {
    suspend fun run(spec: ProcessSpec): ProcessResult

    suspend fun start(spec: ProcessSpec): RunningProcess
}

interface RunningProcess {
    fun events(): Flow<LogEvent>

    suspend fun awaitExit(): Int

    fun terminate()
}

package com.jetbrains.qodana.core.port

import com.jetbrains.qodana.core.model.LogEvent
import com.jetbrains.qodana.core.model.ProcessResult
import com.jetbrains.qodana.core.model.ProcessSpec
import kotlinx.coroutines.flow.Flow

interface ProcessRunner {
    suspend fun run(spec: ProcessSpec): ProcessResult
    suspend fun start(spec: ProcessSpec): RunningProcess
}

interface RunningProcess {
    fun events(): Flow<LogEvent>
    suspend fun awaitExit(): Int
    fun terminate()
}

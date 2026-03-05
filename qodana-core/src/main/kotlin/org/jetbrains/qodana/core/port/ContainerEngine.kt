package org.jetbrains.qodana.core.port

import org.jetbrains.qodana.core.model.ContainerExitStatus
import org.jetbrains.qodana.core.model.ContainerRunSpec
import org.jetbrains.qodana.core.model.LogEvent
import kotlinx.coroutines.flow.Flow

interface ContainerEngine {
    suspend fun pull(image: String, onProgress: (String) -> Unit = {})
    suspend fun create(spec: ContainerRunSpec): String
    suspend fun start(containerId: String)
    fun logs(containerId: String): Flow<LogEvent>
    suspend fun wait(containerId: String): ContainerExitStatus
    suspend fun remove(containerId: String, force: Boolean = false)
    suspend fun info(): ContainerEngineInfo
    suspend fun imageExists(image: String): Boolean
}

data class ContainerEngineInfo(
    val engineType: EngineType,
    val version: String,
    val memoryBytes: Long?,
)

enum class EngineType { DOCKER, PODMAN }

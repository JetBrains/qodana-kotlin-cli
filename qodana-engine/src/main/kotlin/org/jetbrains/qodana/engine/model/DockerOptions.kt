package org.jetbrains.qodana.engine.model

data class DockerOptions(
    val image: String? = null,
    val volumes: List<String> = emptyList(),
    val envVars: Map<String, String> = emptyMap(),
    val user: String? = null,
    val memoryLimit: Long? = null,
    val cpuLimit: Int? = null,
    val skipPull: Boolean = false,
    val noDockerPull: Boolean = false,
)

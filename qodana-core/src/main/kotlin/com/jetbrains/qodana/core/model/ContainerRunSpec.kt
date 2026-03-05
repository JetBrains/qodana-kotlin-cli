package com.jetbrains.qodana.core.model

data class ContainerRunSpec(
    val image: String,
    val mounts: List<MountSpec> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val resources: ResourceLimits = ResourceLimits(),
    val entrypoint: List<String> = emptyList(),
    val cmd: List<String> = emptyList(),
    val user: String? = null,
    val workingDir: String? = null,
    val name: String? = null,
)

data class MountSpec(
    val hostPath: String,
    val containerPath: String,
    val readOnly: Boolean = false,
)

data class ResourceLimits(
    val memoryBytes: Long? = null,
    val cpuCount: Int? = null,
)

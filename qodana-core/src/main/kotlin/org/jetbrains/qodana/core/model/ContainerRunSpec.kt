package org.jetbrains.qodana.core.model

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
    val labels: Map<String, String> = emptyMap(),
    val capAdd: List<String> = emptyList(),
    val securityOpts: List<String> = emptyList(),
    val networkMode: String? = null,
    val tty: Boolean = false,
    val exposedPorts: List<Int> = emptyList(),
    val portBindings: Map<Int, Int> = emptyMap(),
    val autoRemove: Boolean = false,
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

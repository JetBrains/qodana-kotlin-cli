package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.engine.port.ContainerEngine
import org.jetbrains.qodana.core.port.Terminal

class ContainerScan(
    private val containerEngine: ContainerEngine,
    private val terminal: Terminal,
) {
    suspend fun run(context: ScanContext): Int {
        val image = context.docker.image
            ?: throw IllegalStateException("No Docker image specified for container scan")

        if (!context.docker.skipPull && !context.docker.noDockerPull) {
            containerEngine.pull(image) { terminal.println(it) }
        }

        val spec = buildContainerSpec(context, image)
        val containerId = containerEngine.create(spec)

        return try {
            containerEngine.start(containerId)

            coroutineScope {
                launch {
                    containerEngine.logs(containerId)
                        .onEach { event -> terminal.println(event.text) }
                        .collect()
                }

                val exitStatus = containerEngine.wait(containerId)

                if (exitStatus.oomKilled) {
                    terminal.error("Container was killed due to out-of-memory (OOM)")
                }

                exitStatus.exitCode
            }
        } finally {
            val keepContainer = System.getenv("QODANA_CLI_CONTAINER_KEEP")?.isNotBlank() == true
            if (!keepContainer) {
                try {
                    containerEngine.remove(containerId, force = true)
                } catch (_: Exception) {
                    // best effort cleanup
                }
            }
        }
    }

    private fun buildContainerSpec(context: ScanContext, image: String): ContainerRunSpec {
        val mounts = buildList {
            add(MountSpec(
                hostPath = context.paths.projectDir.toString(),
                containerPath = "/data/project",
                readOnly = true,
            ))
            add(MountSpec(
                hostPath = context.paths.resultsDir.toString(),
                containerPath = "/data/results",
            ))
            add(MountSpec(
                hostPath = context.paths.reportDir.toString(),
                containerPath = "/data/report",
            ))
            add(MountSpec(
                hostPath = context.paths.cacheDir.toString(),
                containerPath = "/data/cache",
            ))
            context.runtime.globalConfigDir?.let {
                add(MountSpec(
                    hostPath = it.toString(),
                    containerPath = "/data/config",
                    readOnly = true,
                ))
            }
            context.runtime.coverageDir?.let {
                add(MountSpec(
                    hostPath = it.toString(),
                    containerPath = "/data/coverage",
                    readOnly = true,
                ))
            }
            for (volume in context.docker.volumes) {
                val parts = volume.split(":")
                if (parts.size >= 2) {
                    add(MountSpec(
                        hostPath = parts[0],
                        containerPath = parts[1],
                        readOnly = parts.getOrNull(2) == "ro",
                    ))
                }
            }
        }

        val env = buildMap {
            putAll(context.docker.envVars)
            context.auth.token?.let { put("QODANA_TOKEN", it) }
            context.auth.licenseOnlyToken?.let { put("QODANA_LICENSE_ONLY_TOKEN", it) }
            for ((key, value) in context.runtime.envVars) {
                if (key.startsWith("qodana.", ignoreCase = true) || key.startsWith("QODANA_")) {
                    put(key, value)
                }
            }
        }

        val isDotnet = image.contains("dotnet", ignoreCase = true) ||
            image.contains("cdnet", ignoreCase = true)

        val capAdd = if (isDotnet) listOf("SYS_PTRACE") else emptyList()
        val securityOpts = if (isDotnet) listOf("seccomp=unconfined") else emptyList()

        val portBindings = context.runtime.jvmDebugPort?.let {
            mapOf(5005 to it)
        } ?: emptyMap()

        val exposedPorts = if (portBindings.isNotEmpty()) listOf(5005) else emptyList()

        return ContainerRunSpec(
            image = image,
            mounts = mounts,
            env = env,
            resources = ResourceLimits(
                memoryBytes = context.docker.memoryLimit,
                cpuCount = context.docker.cpuLimit,
            ),
            user = context.docker.user,
            capAdd = capAdd,
            securityOpts = securityOpts,
            portBindings = portBindings,
            exposedPorts = exposedPorts,
            tty = terminal.isInteractive,
        )
    }
}

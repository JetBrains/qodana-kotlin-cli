package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.jetbrains.qodana.core.env.QodanaEnv
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

        if (ContainerImageUtils.isUnofficialLinter(image)) {
            terminal.warn("You are using an unofficial Qodana linter: $image")
        }
        if (!ContainerImageUtils.hasExactVersionTag(image)) {
            terminal.warn("You are running a Qodana linter without an exact version tag: $image")
        } else if (!ContainerImageUtils.isCompatibleLinter(image)) {
            terminal.warn("You are using a non-compatible Qodana linter $image with the current CLI")
        }

        val outputRenderer = TerminalStreamRenderer(terminal)
        if (!context.docker.skipPull && !context.docker.noDockerPull) {
            try {
                containerEngine.pull(image) { progress ->
                    if (terminal.isInteractive) {
                        outputRenderer.renderInPlace(progress)
                    } else {
                        terminal.println(progress)
                    }
                }
            } finally {
                outputRenderer.ensureLineBreak()
            }
        }

        val spec = buildContainerSpec(context, image)
        val containerId = containerEngine.create(spec)

        return try {
            containerEngine.start(containerId)

            coroutineScope {
                val logsJob = launch {
                    containerEngine.logs(containerId)
                        .onEach { event -> outputRenderer.render(event.text) }
                        .collect()
                }

                val exitStatus = containerEngine.wait(containerId)
                logsJob.join()
                outputRenderer.ensureLineBreak()

                if (exitStatus.oomKilled) {
                    terminal.error("Container was killed due to out-of-memory (OOM)")
                }

                exitStatus.exitCode
            }
        } finally {
            outputRenderer.ensureLineBreak()
            val keepContainer = System.getenv(QodanaEnv.CLI_CONTAINER_KEEP)?.isNotBlank() == true
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
                hostPath = context.paths.repositoryRoot.toString(),
                containerPath = "/data/project",
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

        val containerName = System.getenv(QodanaEnv.CLI_CONTAINER_NAME)
        val resolvedUser = ContainerImageUtils.selectUser(image, context.docker.user ?: "auto")
            .ifBlank { null }
        val cmd = IdeArgBuilder.build(context)

        return ContainerRunSpec(
            image = image,
            name = containerName,
            mounts = mounts,
            env = env,
            resources = ResourceLimits(
                memoryBytes = context.docker.memoryLimit,
                cpuCount = context.docker.cpuLimit,
            ),
            user = resolvedUser,
            cmd = cmd,
            capAdd = capAdd,
            securityOpts = securityOpts,
            portBindings = portBindings,
            exposedPorts = exposedPorts,
            tty = terminal.isInteractive,
        )
    }
}

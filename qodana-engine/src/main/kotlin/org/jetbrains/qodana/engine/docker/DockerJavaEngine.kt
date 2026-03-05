package org.jetbrains.qodana.engine.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.engine.port.ContainerEngine
import org.jetbrains.qodana.engine.port.ContainerEngineInfo
import org.jetbrains.qodana.engine.port.EngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Ports

class DockerJavaEngine : ContainerEngine {

    private val config: DefaultDockerClientConfig by lazy { createConfig() }
    private val client: DockerClient by lazy { createClient(config) }

    private fun createConfig(): DefaultDockerClientConfig {
        val dockerHost = resolveDockerHost()
        return DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerHost)
            .build()
    }

    private fun createClient(config: DefaultDockerClientConfig): DockerClient {
        val httpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(60))
            .build()
        return DockerClientImpl.getInstance(config, httpClient)
    }

    override suspend fun pull(image: String, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("Pulling image $image...")
        val pullCmd = client.pullImageCmd(image)

        try {
            val repoName = com.github.dockerjava.core.NameParser.resolveRepositoryName(image)
            val authConfig = config.effectiveAuthConfig(repoName.reposName)
            if (authConfig != null) {
                pullCmd.withAuthConfig(authConfig)
            }
        } catch (_: Exception) {
            // No auth config available — proceed without auth
        }

        pullCmd.exec(object : ResultCallback.Adapter<PullResponseItem>() {
                override fun onNext(item: PullResponseItem) {
                    val status = buildString {
                        append(item.status ?: "")
                        val detail = item.progressDetail
                        if (detail?.current != null) {
                            append(" ${detail.current}/${detail.total ?: "?"}")
                        }
                    }
                    if (status.isNotBlank()) onProgress(status)
                }
            })
            .awaitCompletion()
        onProgress("Image $image pulled successfully")
    }

    override suspend fun create(spec: ContainerRunSpec): String = withContext(Dispatchers.IO) {
        val cmd = client.createContainerCmd(spec.image)

        if (spec.env.isNotEmpty()) {
            cmd.withEnv(spec.env.map { (k, v) -> "$k=$v" })
        }

        val hostConfig = HostConfig.newHostConfig()

        if (spec.mounts.isNotEmpty()) {
            hostConfig.withBinds(spec.mounts.map { mount ->
                Bind(
                    mount.hostPath,
                    Volume(mount.containerPath),
                    if (mount.readOnly) AccessMode.ro else AccessMode.rw,
                )
            })
        }

        spec.resources.memoryBytes?.let { hostConfig.withMemory(it) }
        spec.resources.cpuCount?.let { hostConfig.withCpuCount(it.toLong()) }

        if (spec.capAdd.isNotEmpty()) {
            hostConfig.withCapAdd(*spec.capAdd.map { Capability.valueOf(it) }.toTypedArray())
        }

        if (spec.securityOpts.isNotEmpty()) {
            hostConfig.withSecurityOpts(spec.securityOpts)
        }

        spec.networkMode?.let { hostConfig.withNetworkMode(it) }

        if (spec.autoRemove) {
            hostConfig.withAutoRemove(true)
        }

        if (spec.portBindings.isNotEmpty()) {
            val ports = Ports()
            spec.portBindings.forEach { (containerPort, hostPort) ->
                ports.bind(ExposedPort.tcp(containerPort), Ports.Binding.bindPort(hostPort))
            }
            hostConfig.withPortBindings(ports)
        }

        cmd.withHostConfig(hostConfig)

        if (spec.exposedPorts.isNotEmpty()) {
            cmd.withExposedPorts(spec.exposedPorts.map { ExposedPort.tcp(it) })
        }

        if (spec.entrypoint.isNotEmpty()) {
            cmd.withEntrypoint(spec.entrypoint)
        }

        if (spec.cmd.isNotEmpty()) {
            cmd.withCmd(spec.cmd)
        }

        if (spec.tty) {
            cmd.withTty(true)
            cmd.withAttachStdout(true)
            cmd.withAttachStderr(true)
        }

        if (spec.labels.isNotEmpty()) {
            cmd.withLabels(spec.labels)
        }

        spec.user?.let { cmd.withUser(it) }
        spec.workingDir?.let { cmd.withWorkingDir(it) }
        spec.name?.let { cmd.withName(it) }

        cmd.exec().id
    }

    override suspend fun start(containerId: String): Unit = withContext(Dispatchers.IO) {
        client.startContainerCmd(containerId).exec()
    }

    override fun logs(containerId: String): Flow<LogEvent> = channelFlow {
        val callback = object : ResultCallback.Adapter<Frame>() {
            override fun onNext(frame: Frame) {
                val stream = when (frame.streamType) {
                    StreamType.STDERR -> Stream.STDERR
                    else -> Stream.STDOUT
                }
                val text = String(frame.payload).trimEnd('\n', '\r')
                if (text.isNotEmpty()) {
                    trySend(
                        LogEvent(
                            source = LogSource.CONTAINER,
                            stream = stream,
                            text = text,
                        )
                    )
                }
            }
        }

        withContext(Dispatchers.IO) {
            client.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withTailAll()
                .exec(callback)
                .awaitCompletion()
        }
    }

    override suspend fun wait(containerId: String): ContainerExitStatus = withContext(Dispatchers.IO) {
        val adapter = client.waitContainerCmd(containerId)
            .exec(ResultCallback.Adapter<WaitResponse>())
        adapter.awaitCompletion()

        val inspection = client.inspectContainerCmd(containerId).exec()
        val exitCode = inspection.state?.exitCodeLong?.toInt() ?: 0
        val oomKilled = inspection.state?.oomKilled ?: false

        ContainerExitStatus(
            exitCode = exitCode,
            oomKilled = oomKilled,
        )
    }

    override suspend fun remove(containerId: String, force: Boolean) = withContext(Dispatchers.IO) {
        client.removeContainerCmd(containerId)
            .withForce(force)
            .exec()
        Unit
    }

    override suspend fun info(): ContainerEngineInfo = withContext(Dispatchers.IO) {
        val info = client.infoCmd().exec()
        val version = client.versionCmd().exec()

        val engineType = if (isRunningPodman(info)) EngineType.PODMAN else EngineType.DOCKER

        ContainerEngineInfo(
            engineType = engineType,
            version = version.version ?: "unknown",
            memoryBytes = info.memTotal,
        )
    }

    override suspend fun imageExists(image: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.inspectImageCmd(image).exec()
            true
        } catch (_: com.github.dockerjava.api.exception.NotFoundException) {
            false
        }
    }

    private fun isRunningPodman(info: com.github.dockerjava.api.model.Info): Boolean {
        val serverVersion = info.serverVersion ?: ""
        val operatingSystem = info.operatingSystem ?: ""
        return serverVersion.contains("podman", ignoreCase = true) ||
            operatingSystem.contains("podman", ignoreCase = true)
    }

    companion object {
        private const val DOCKER_SOCKET = "/var/run/docker.sock"
        private const val PODMAN_SOCKET_ROOTFUL = "/run/podman/podman.sock"

        fun resolveDockerHost(): String {
            val envHost = System.getenv("DOCKER_HOST")
            if (!envHost.isNullOrBlank()) return envHost

            if (Files.exists(Path.of(DOCKER_SOCKET))) {
                return "unix://$DOCKER_SOCKET"
            }

            if (Files.exists(Path.of(PODMAN_SOCKET_ROOTFUL))) {
                return "unix://$PODMAN_SOCKET_ROOTFUL"
            }

            val uid = try {
                ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
            } catch (_: Exception) {
                null
            }
            if (uid != null) {
                val podmanRootless = "/run/user/$uid/podman/podman.sock"
                if (Files.exists(Path.of(podmanRootless))) {
                    return "unix://$podmanRootless"
                }
            }

            // Fall back to default Docker socket
            return "unix://$DOCKER_SOCKET"
        }
    }
}

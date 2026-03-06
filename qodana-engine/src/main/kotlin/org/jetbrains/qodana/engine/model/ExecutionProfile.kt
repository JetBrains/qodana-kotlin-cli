package org.jetbrains.qodana.engine.model

import org.jetbrains.qodana.engine.env.RuntimeEnvironment
import org.jetbrains.qodana.engine.env.RuntimeEnvironmentDetector

sealed interface ExecutionProfile {
    val kind: Kind
    val analysisRuntime: AnalysisRuntime
    val runtimeEnvironment: RuntimeEnvironment
    val pathLayout: PathLayout
    val canInstallIde: Boolean
    val canRunBootstrap: Boolean

    enum class Kind {
        NATIVE,
        IN_DOCKER,
        DOCKER_LAUNCHER,
    }

    enum class AnalysisRuntime {
        NATIVE,
        CONTAINER,
    }

    enum class PathLayout {
        HOST,
        IN_DOCKER,
    }
}

data object NativeExecutionProfile : ExecutionProfile {
    override val kind: ExecutionProfile.Kind = ExecutionProfile.Kind.NATIVE
    override val analysisRuntime: ExecutionProfile.AnalysisRuntime = ExecutionProfile.AnalysisRuntime.NATIVE
    override val runtimeEnvironment: RuntimeEnvironment = RuntimeEnvironment.HOST
    override val pathLayout: ExecutionProfile.PathLayout = ExecutionProfile.PathLayout.HOST
    override val canInstallIde: Boolean = true
    override val canRunBootstrap: Boolean = true
}

data object InDockerExecutionProfile : ExecutionProfile {
    override val kind: ExecutionProfile.Kind = ExecutionProfile.Kind.IN_DOCKER
    override val analysisRuntime: ExecutionProfile.AnalysisRuntime = ExecutionProfile.AnalysisRuntime.CONTAINER
    override val runtimeEnvironment: RuntimeEnvironment = RuntimeEnvironment.IN_DOCKER
    override val pathLayout: ExecutionProfile.PathLayout = ExecutionProfile.PathLayout.IN_DOCKER
    override val canInstallIde: Boolean = false
    override val canRunBootstrap: Boolean = false
}

data object DockerLauncherExecutionProfile : ExecutionProfile {
    override val kind: ExecutionProfile.Kind = ExecutionProfile.Kind.DOCKER_LAUNCHER
    override val analysisRuntime: ExecutionProfile.AnalysisRuntime = ExecutionProfile.AnalysisRuntime.CONTAINER
    override val runtimeEnvironment: RuntimeEnvironment = RuntimeEnvironment.HOST
    override val pathLayout: ExecutionProfile.PathLayout = ExecutionProfile.PathLayout.HOST
    override val canInstallIde: Boolean = false
    override val canRunBootstrap: Boolean = false
}

enum class RequestedExecutionTarget {
    NATIVE,
    CONTAINER,
}

class ExecutionProfileResolver(
    private val detectRuntimeEnvironment: () -> RuntimeEnvironment = { RuntimeEnvironmentDetector.detect() },
) {
    fun resolve(requestedTarget: RequestedExecutionTarget): ExecutionProfile {
        if (requestedTarget == RequestedExecutionTarget.NATIVE) {
            return NativeExecutionProfile
        }
        return when (detectRuntimeEnvironment()) {
            RuntimeEnvironment.HOST -> DockerLauncherExecutionProfile
            RuntimeEnvironment.IN_DOCKER -> InDockerExecutionProfile
        }
    }
}

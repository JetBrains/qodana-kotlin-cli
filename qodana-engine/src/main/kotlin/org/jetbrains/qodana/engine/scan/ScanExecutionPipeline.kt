package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.engine.model.DockerLauncherExecutionProfile
import org.jetbrains.qodana.engine.model.ExecutionProfile
import org.jetbrains.qodana.engine.model.InDockerExecutionProfile
import org.jetbrains.qodana.engine.model.NativeExecutionProfile
import org.jetbrains.qodana.engine.model.ScanContext
import org.jetbrains.qodana.engine.startup.PrepareHost
import org.jetbrains.qodana.engine.startup.PreparedHost

abstract class BaseScanExecutionPipeline(
    private val prepareHost: PrepareHost,
) {
    open fun prepare(context: ScanContext): PreparedHost = prepareHost.prepare(context)

    abstract fun canRunBootstrap(context: ScanContext): Boolean

    abstract suspend fun runAnalysis(context: ScanContext): Int
}

class NativeScanExecutionPipeline(
    prepareHost: PrepareHost,
    private val nativeScan: NativeScan,
) : BaseScanExecutionPipeline(prepareHost) {
    override fun canRunBootstrap(context: ScanContext): Boolean = true

    override suspend fun runAnalysis(context: ScanContext): Int = nativeScan.run(context)
}

abstract class BaseContainerScanExecutionPipeline(
    prepareHost: PrepareHost,
    private val containerScan: ContainerScan,
) : BaseScanExecutionPipeline(prepareHost) {
    override fun canRunBootstrap(context: ScanContext): Boolean = false

    override suspend fun runAnalysis(context: ScanContext): Int = containerScan.run(context)
}

class InDockerScanExecutionPipeline(
    prepareHost: PrepareHost,
    containerScan: ContainerScan,
) : BaseContainerScanExecutionPipeline(prepareHost, containerScan)

class DockerLauncherScanExecutionPipeline(
    prepareHost: PrepareHost,
    containerScan: ContainerScan,
) : BaseContainerScanExecutionPipeline(prepareHost, containerScan)

class ScanExecutionPipelineFactory(
    private val nativePipeline: NativeScanExecutionPipeline,
    private val inDockerPipeline: InDockerScanExecutionPipeline,
    private val dockerLauncherPipeline: DockerLauncherScanExecutionPipeline,
) {
    fun create(profile: ExecutionProfile): BaseScanExecutionPipeline =
        when (profile) {
            NativeExecutionProfile -> nativePipeline
            InDockerExecutionProfile -> inDockerPipeline
            DockerLauncherExecutionProfile -> dockerLauncherPipeline
        }
}

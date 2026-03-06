package org.jetbrains.qodana.engine.startup

import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.ScanPaths
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.core.product.nativeAnalyzer
import org.jetbrains.qodana.engine.model.ExecutionProfile
import org.jetbrains.qodana.engine.model.ScanContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

class PrepareHost(
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
    private val ideInstaller: IdeInstaller? = null,
) {
    private val preparers: Map<ExecutionProfile.Kind, BaseHostPreparer> = mapOf(
        ExecutionProfile.Kind.NATIVE to NativeHostPreparer(fileSystem, terminal, ideInstaller),
        ExecutionProfile.Kind.IN_DOCKER to InDockerHostPreparer(fileSystem, terminal),
        ExecutionProfile.Kind.DOCKER_LAUNCHER to DockerLauncherHostPreparer(fileSystem, terminal),
    )

    fun prepare(context: ScanContext): PreparedHost {
        return preparers.getValue(context.executionProfile.kind).prepare(context)
    }
}

private abstract class BaseHostPreparer(
    private val fileSystem: FileSystem,
    protected val terminal: Terminal,
) {
    protected val log = LoggerFactory.getLogger(this::class.java)

    fun prepare(context: ScanContext): PreparedHost {
        ensureDirectories(context.paths)

        if (context.runtime.clearCache) {
            terminal.println("Clearing cache directory...")
            fileSystem.delete(context.paths.cacheDir)
            fileSystem.createDirectories(context.paths.cacheDir)
        }

        logEnvironment()

        return PreparedHost(
            ideDir = resolveIdeDir(context),
            uploadToken = context.auth.token,
        )
    }

    protected open fun resolveIdeDir(context: ScanContext): Path? = context.runtime.ideDir

    private fun ensureDirectories(paths: ScanPaths) {
        fileSystem.createDirectories(paths.resultsDir)
        fileSystem.createDirectories(paths.reportDir)
        fileSystem.createDirectories(paths.cacheDir)
    }

    private fun logEnvironment() {
        if (!System.getenv(QodanaEnv.CLEAR_KEYRING).isNullOrBlank()) {
            log.info("QODANA_CLEAR_KEYRING is set, keyring will be cleared on token load")
        }

        val androidSdk = System.getenv(QodanaEnv.ANDROID_SDK_ROOT)
        if (!androidSdk.isNullOrBlank()) {
            log.info("Android SDK root: {}", androidSdk)
        }
        val corettoSdk = System.getenv(QodanaEnv.CORETTO_SDK)
        if (!corettoSdk.isNullOrBlank()) {
            log.info("Corretto SDK: {}", corettoSdk)
        }

        val license = System.getenv(QodanaEnv.LICENSE)
        if (!license.isNullOrBlank()) {
            log.info("QODANA_LICENSE is set")
        }
    }
}

private class NativeHostPreparer(
    fileSystem: FileSystem,
    terminal: Terminal,
    private val ideInstaller: IdeInstaller?,
) : BaseHostPreparer(fileSystem, terminal) {
    override fun resolveIdeDir(context: ScanContext): Path? {
        var ideDir = context.runtime.ideDir
        if (ideDir == null && ideInstaller != null) {
            val linter = context.linter?.let { Linters.findByName(it) }
            if (linter != null && linter.supportsNative) {
                val analyzer = linter.nativeAnalyzer()
                ideDir = runBlocking {
                    ideInstaller.downloadAndInstall(analyzer, context.paths.cacheDir)
                }
                terminal.println("IDE installed to $ideDir")
            }
        }
        return ideDir
    }
}

private class InDockerHostPreparer(
    fileSystem: FileSystem,
    terminal: Terminal,
) : BaseHostPreparer(fileSystem, terminal)

private class DockerLauncherHostPreparer(
    fileSystem: FileSystem,
    terminal: Terminal,
) : BaseHostPreparer(fileSystem, terminal)

data class PreparedHost(
    val ideDir: Path?,
    val uploadToken: String?,
)

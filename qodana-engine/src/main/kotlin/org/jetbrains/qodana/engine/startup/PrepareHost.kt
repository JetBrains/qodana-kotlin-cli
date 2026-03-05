package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.core.product.nativeAnalyzer
import org.jetbrains.qodana.engine.model.ScanContext
import org.jetbrains.qodana.core.model.ScanPaths
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.slf4j.LoggerFactory
import java.nio.file.Path

class PrepareHost(
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
    private val ideInstaller: IdeInstaller? = null,
) {
    private val log = LoggerFactory.getLogger(PrepareHost::class.java)

    fun prepare(context: ScanContext): PreparedHost {
        ensureDirectories(context.paths)

        if (context.runtime.clearCache) {
            terminal.println("Clearing cache directory...")
            fileSystem.delete(context.paths.cacheDir)
            fileSystem.createDirectories(context.paths.cacheDir)
        }

        // Handle QODANA_CLEAR_KEYRING
        if (!System.getenv(QodanaEnv.CLEAR_KEYRING).isNullOrBlank()) {
            log.info("QODANA_CLEAR_KEYRING is set, keyring will be cleared on token load")
        }

        // Check for Android SDK configuration
        val androidSdk = System.getenv(QodanaEnv.ANDROID_SDK_ROOT)
        if (!androidSdk.isNullOrBlank()) {
            log.info("Android SDK root: {}", androidSdk)
        }
        val corettoSdk = System.getenv(QodanaEnv.CORETTO_SDK)
        if (!corettoSdk.isNullOrBlank()) {
            log.info("Corretto SDK: {}", corettoSdk)
        }

        // Handle QODANA_LICENSE passthrough
        val license = System.getenv(QodanaEnv.LICENSE)
        if (!license.isNullOrBlank()) {
            log.info("QODANA_LICENSE is set")
        }

        var ideDir = context.runtime.ideDir
        if (ideDir == null && context.nativeMode && ideInstaller != null) {
            val linter = context.linter?.let { Linters.findByName(it) }
            if (linter != null && linter.supportsNative) {
                val analyzer = linter.nativeAnalyzer()
                ideDir = kotlinx.coroutines.runBlocking {
                    ideInstaller.downloadAndInstall(analyzer, context.paths.cacheDir)
                }
                terminal.println("IDE installed to $ideDir")
            }
        }

        return PreparedHost(
            ideDir = ideDir,
            uploadToken = context.auth.token,
        )
    }

    private fun ensureDirectories(paths: ScanPaths) {
        fileSystem.createDirectories(paths.resultsDir)
        fileSystem.createDirectories(paths.reportDir)
        fileSystem.createDirectories(paths.cacheDir)
    }
}

data class PreparedHost(
    val ideDir: Path?,
    val uploadToken: String?,
)

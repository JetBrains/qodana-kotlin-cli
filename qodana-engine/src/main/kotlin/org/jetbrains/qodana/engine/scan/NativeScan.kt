package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.model.Stream
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.product.IntellijLinterProperties
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.engine.env.CiDetector
import org.jetbrains.qodana.engine.model.ScanContext
import org.jetbrains.qodana.engine.startup.CacheSync
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Runs the IDE inspection locally (native mode, no Docker).
 * Mirrors Go's `runQodanaLocal()` in `ide.go`.
 *
 * Flow:
 * 1. Discover IDE product via [IdeProductDiscovery.guessProduct]
 * 2. Write VM options via [PropertyGenerator]
 * 3. Build command: `<ideScript> [inspect] qodana <args> <projectDir> <resultsDir>`
 * 4. Execute IDE through [ProcessRunner], stream stdout/stderr
 * 5. Read exit code from SARIF when available
 */
class NativeScan(
    private val processRunner: ProcessRunner,
    private val fileSystem: FileSystem,
    private val terminal: Terminal? = null,
) {
    private val log = LoggerFactory.getLogger(NativeScan::class.java)

    suspend fun run(context: ScanContext): Int {
        // Resolve IDE home directory
        val distOverride = System.getenv(QodanaEnv.DIST)
        val ideDir =
            if (!distOverride.isNullOrBlank()) {
                Path.of(distOverride)
            } else {
                context.runtime.ideDir
                    ?: throw IllegalStateException("Native scan requires ideDir to be set")
            }

        // Discover IDE product (matches Go's GuessProduct)
        val product = IdeProductDiscovery.guessProduct(ideDir, fileSystem)
        System.setProperty(QodanaEnv.DIST, product.home)

        // Unset Ruby variables if this is a Ruby linter
        val linter = context.linter?.let { Linters.findByName(it) }
        if (linter != null && Linters.isRuby(linter)) {
            CiDetector.unsetRubyVariables()
        }

        val configDir = context.paths.cacheDir.resolve("idea-config")
        fileSystem.createDirectories(configDir)
        val cacheSync = CacheSync(fileSystem, CACHE_SYNC_TERMINAL)
        syncCacheBeforeAnalysis(cacheSync, context, product, configDir)

        installPlugins(context, product, configDir)

        try {
            // Write VM options
            val runEnv = writeProperties(context, product, configDir)

            // Build IDE command — matches Go's getIdeRunCommand()
            val args = getIdeRunCommand(product, context)
            log.info("Starting native IDE scan: {}", args.joinToString(" "))

            val spec =
                ProcessSpec(
                    command = args[0],
                    args = args.drop(1),
                    workDir = context.paths.projectDir,
                    timeout = context.runtime.timeout,
                    env = runEnv,
                )

            val process = processRunner.start(spec)
            val outputRenderer = terminal?.let { TerminalStreamRenderer(it) }

            process
                .events()
                .onEach { event ->
                    // Match Go behavior: native analyzer output is streamed to CLI in real time.
                    if (outputRenderer != null) {
                        outputRenderer.render(event.text)
                        log.debug("[IDE][{}] {}", event.stream, event.text)
                    } else {
                        when (event.stream) {
                            Stream.STDOUT -> log.info("[IDE] {}", event.text)
                            Stream.STDERR -> log.warn("[IDE] {}", event.text)
                        }
                    }
                }.collect()
            outputRenderer?.ensureLineBreak()

            val processExitCode = process.awaitExit()
            log.info("IDE process exited with code {}", processExitCode)

            return readIdeExitCode(context.paths.resultsDir, processExitCode)
        } finally {
            syncCacheAfterAnalysis(cacheSync, context, product, configDir)
        }
    }

    private suspend fun installPlugins(
        context: ScanContext,
        product: IdeProduct,
        configDir: Path,
    ) {
        val plugins = context.yaml?.plugins ?: emptyList()
        if (plugins.isEmpty()) return
        val installVmOptionsPath = configDir.resolve("install_plugins.vmoptions")
        fileSystem.write(installVmOptionsPath, generateInstallPluginsVmOptions(context, product, configDir))
        val installEnv = buildVmOptionsEnv(context, product, installVmOptionsPath)
        for (plugin in plugins) {
            if (plugin.id.isBlank()) continue
            log.info("Installing plugin {}", plugin.id)
            val result =
                processRunner.run(
                    ProcessSpec(
                        command = product.ideScript,
                        args = listOf("installPlugins", plugin.id),
                        workDir = context.paths.projectDir,
                        timeout = context.runtime.timeout,
                        env = installEnv,
                    ),
                )
            if (!result.isSuccess) {
                throw IllegalStateException("Failed to install plugin ${plugin.id}: ${result.stderr}")
            }
        }
    }

    private fun generateInstallPluginsVmOptions(
        context: ScanContext,
        product: IdeProduct,
        configDir: Path,
    ): String {
        val lines =
            listOf(
                "-Didea.config.path=$configDir",
                "-Didea.system.path=${context.paths.cacheDir.resolve("idea-system")}",
                "-Didea.plugins.path=${context.paths.cacheDir.resolve("idea-plugins")}",
                "-Didea.log.path=${context.paths.resultsDir.resolve("log")}",
                "-Didea.headless.enable.statistics=false",
                "-Dqodana.application=true",
                "-Dintellij.platform.load.app.info.from.resources=true",
                "-Dqodana.build.number=${product.ideCode}-${product.build}",
            )
        return lines.joinToString(separator = "\n")
    }

    /**
     * Matches Go's `getIdeRunCommand()`:
     * `[ideScript] [inspect] qodana <args> <projectDir> <resultsDir>`
     */
    private fun getIdeRunCommand(
        product: IdeProduct,
        context: ScanContext,
    ): List<String> =
        buildList {
            add(product.ideScript)
            if (!product.is242orNewer()) {
                add("inspect")
            }
            add("qodana")
            addAll(IdeArgBuilder.build(context, product))
            add(context.paths.projectDir.toString())
            add(context.paths.resultsDir.toString())
        }

    private fun writeProperties(
        context: ScanContext,
        product: IdeProduct,
        configDir: Path,
    ): Map<String, String> {
        PropertyGenerator.writeTo(context, configDir, customPluginVmOptions(product)) { path, content ->
            fileSystem.write(path, content)
        }
        log.debug("Wrote IDE property files to {}", configDir)
        return buildVmOptionsEnv(context, product, configDir.resolve("idea64.vmoptions"))
    }

    /**
     * Mirrors the Go CLI's `getCustomPluginPaths` + `DisabledPluginsFilePath`. IDEs that do NOT bundle
     * `org.intellij.qodana` (PyCharm/RubyMine/CLion/GoLand) ship it — and a curated
     * `disabled_plugins.txt` — in the dist's `custom-plugins/` dir. Load the plugin dirs via
     * `-Dplugin.path` (else `QodanaApplicationStarter` is never registered → the IDE aborts with
     * "Application cannot start in a headless mode"), and apply the disabled list via
     * `-Ddisabled.plugins.file.path` (it disables HtmlTools so CLion's bundled Angular cascade-disables
     * instead of fatally erroring on its missing dependency). Absent dir → empty (IDEs that bundle qodana).
     */
    private fun customPluginVmOptions(product: IdeProduct): List<String> {
        val customPluginsDir = Path.of(product.home).resolve("custom-plugins")
        if (!fileSystem.exists(customPluginsDir)) return emptyList()

        val options = mutableListOf<String>()
        val nestedDisabledPlugins = customPluginsDir.resolve("disabled_plugins.txt")
        val pluginDirs =
            fileSystem
                .walk(customPluginsDir)
                .filter { it.parent == customPluginsDir && it != nestedDisabledPlugins }
                .toList()
        if (pluginDirs.isNotEmpty()) {
            options += "-Dplugin.path=${pluginDirs.joinToString(",")}"
        }
        // The disabled list ships at the dist root (the Go CLI's container path) or inside custom-plugins/.
        listOf(Path.of(product.home).resolve("disabled_plugins.txt"), nestedDisabledPlugins)
            .firstOrNull { fileSystem.exists(it) }
            ?.let { options += "-Ddisabled.plugins.file.path=$it" }
        return options
    }

    private fun buildVmOptionsEnv(
        context: ScanContext,
        product: IdeProduct,
        vmOptionsPath: Path,
    ): Map<String, String> {
        val linterProperties =
            context.linter?.let { Linters.findByName(it) }?.let { IntellijLinterProperties.findByLinter(it) }
                ?: IntellijLinterProperties.findByProductInfoCode(product.ideCode)
                ?: return context.runtime.envVars
        return context.runtime.envVars + (linterProperties.vmOptionsEnv to vmOptionsPath.toString())
    }

    private fun syncCacheBeforeAnalysis(
        cacheSync: CacheSync,
        context: ScanContext,
        product: IdeProduct,
        configDir: Path,
    ) {
        runCatching {
            cacheSync.syncIdeaCache(context.paths.cacheDir, context.paths.projectDir, overwrite = false)
        }.onFailure {
            log.warn("Failed to sync IDE cache before analysis: {}", it.message)
        }
        runCatching {
            cacheSync.syncConfigCache(
                confDirPath = configDir,
                cacheDir = context.paths.cacheDir,
                versionBranch = product.getVersionBranch(),
                fromCache = true,
            )
        }.onFailure {
            log.warn("Failed to sync config cache before analysis: {}", it.message)
        }
    }

    private fun syncCacheAfterAnalysis(
        cacheSync: CacheSync,
        context: ScanContext,
        product: IdeProduct,
        configDir: Path,
    ) {
        runCatching {
            cacheSync.syncIdeaCache(context.paths.projectDir, context.paths.cacheDir, overwrite = true)
        }.onFailure {
            log.warn("Failed to sync IDE cache after analysis: {}", it.message)
        }
        runCatching {
            cacheSync.syncConfigCache(
                confDirPath = configDir,
                cacheDir = context.paths.cacheDir,
                versionBranch = product.getVersionBranch(),
                fromCache = false,
            )
        }.onFailure {
            log.warn("Failed to sync config cache after analysis: {}", it.message)
        }
    }

    private fun readIdeExitCode(
        resultsDir: Path,
        processExitCode: Int,
    ): Int {
        if (processExitCode != 0) {
            log.info("IDE process exited with non-zero code {}; SARIF exit code is ignored", processExitCode)
            return processExitCode
        }

        val sarifPath = resultsDir.resolve(SARIF_FILENAME)
        if (!fileSystem.exists(sarifPath)) {
            log.debug("No SARIF file at {}; using process exit code {}", sarifPath, processExitCode)
            return processExitCode
        }

        return try {
            val content = fileSystem.read(sarifPath)
            val match = EXIT_CODE_PATTERN.find(content)
            if (match != null) {
                val sarifExitCode = match.groupValues[1].toInt()
                if (sarifExitCode < 0 || sarifExitCode > 255) {
                    log.warn("Wrong exitCode in SARIF: {}. Falling back to 1", sarifExitCode)
                    return 1
                }
                log.info("SARIF exit code: {} (process exit code was {})", sarifExitCode, processExitCode)
                sarifExitCode
            } else {
                log.debug("No exitCode field in SARIF; using process exit code {}", processExitCode)
                processExitCode
            }
        } catch (e: Exception) {
            log.warn("Failed to read exit code from SARIF: {}", e.message)
            processExitCode
        }
    }

    companion object {
        private const val SARIF_FILENAME = "qodana.sarif.json"
        private val EXIT_CODE_PATTERN = Regex(""""exitCode"\s*:\s*(\d+)""")
        private val CACHE_SYNC_TERMINAL =
            object : Terminal {
                override val isInteractive: Boolean = false
                override var isCi: Boolean = false

                override fun print(message: String) {}

                override fun println(message: String) {}

                override fun error(message: String) {}

                override fun info(message: String) {}

                override fun warn(message: String) {}

                override fun debug(message: String) {}

                override fun <T> spinner(
                    message: String,
                    action: () -> T,
                ): T = action()

                override fun prompt(
                    message: String,
                    default: String?,
                ): String = default ?: ""

                override fun select(
                    message: String,
                    choices: List<String>,
                ): String = choices.firstOrNull() ?: ""

                override fun setRedactedTokens(tokens: Set<String>) {}
            }
    }
}

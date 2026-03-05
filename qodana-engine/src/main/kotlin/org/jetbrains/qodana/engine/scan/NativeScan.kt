package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.model.Stream
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.engine.env.CiDetector
import org.jetbrains.qodana.engine.model.ScanContext
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
) {

    private val log = LoggerFactory.getLogger(NativeScan::class.java)

    suspend fun run(context: ScanContext): Int {
        // Resolve IDE home directory
        val distOverride = System.getenv(QodanaEnv.DIST)
        val ideDir = if (!distOverride.isNullOrBlank()) {
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

        // Write VM options
        writeProperties(context, product)

        // Build IDE command — matches Go's getIdeRunCommand()
        val args = getIdeRunCommand(product, context)
        log.info("Starting native IDE scan: {}", args.joinToString(" "))

        val spec = ProcessSpec(
            command = args[0],
            args = args.drop(1),
            workDir = context.paths.projectDir,
            timeout = context.runtime.timeout,
            env = context.runtime.envVars,
        )

        val process = processRunner.start(spec)

        process.events()
            .onEach { event ->
                when (event.stream) {
                    Stream.STDOUT -> log.info("[IDE] {}", event.text)
                    Stream.STDERR -> log.warn("[IDE] {}", event.text)
                }
            }
            .collect()

        val processExitCode = process.awaitExit()
        log.info("IDE process exited with code {}", processExitCode)

        return readIdeExitCode(context.paths.resultsDir, processExitCode)
    }

    /**
     * Matches Go's `getIdeRunCommand()`:
     * `[ideScript] [inspect] qodana <args> <projectDir> <resultsDir>`
     */
    private fun getIdeRunCommand(product: IdeProduct, context: ScanContext): List<String> = buildList {
        add(product.ideScript)
        if (!product.is242orNewer()) {
            add("inspect")
        }
        add("qodana")
        addAll(IdeArgBuilder.build(context, product))
        add(context.paths.projectDir.toString())
        add(context.paths.resultsDir.toString())
    }

    private fun writeProperties(context: ScanContext, product: IdeProduct) {
        val configDir = context.paths.cacheDir.resolve("idea-config")
        fileSystem.createDirectories(configDir)

        PropertyGenerator.writeTo(context, configDir) { path, content ->
            fileSystem.write(path, content)
        }

        // Set VM options env var like Go does
        val linterProps = context.linter?.let { Linters.findByName(it) }
            ?.let { org.jetbrains.qodana.core.product.IntellijLinterProperties.findByLinter(it) }
        if (linterProps != null) {
            val vmOptionsPath = configDir.resolve("idea64.vmoptions").toString()
            System.setProperty(linterProps.vmOptionsEnv, vmOptionsPath)
        }

        log.debug("Wrote IDE property files to {}", configDir)
    }

    private fun readIdeExitCode(resultsDir: Path, processExitCode: Int): Int {
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
    }
}

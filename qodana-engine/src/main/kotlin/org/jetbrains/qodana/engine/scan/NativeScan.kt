package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.engine.model.ScanContext
import org.jetbrains.qodana.core.model.Stream
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Runs the IDE inspection locally (native mode, no Docker).
 *
 * Flow:
 * 1. Write `idea.properties` and VM options via [PropertyGenerator]
 * 2. Build IDE command-line via [IdeArgBuilder]
 * 3. Execute the IDE through [ProcessRunner] with the configured timeout
 * 4. Stream stdout/stderr as log events
 * 5. Read the actual exit code from the SARIF result when available
 */
class NativeScan(
    private val processRunner: ProcessRunner,
    private val fileSystem: FileSystem,
) {

    private val log = LoggerFactory.getLogger(NativeScan::class.java)

    /**
     * Execute the full native scan and return the final exit code.
     */
    suspend fun run(context: ScanContext): Int {
        val ideDir = context.runtime.ideDir
            ?: throw IllegalStateException("Native scan requires ideDir to be set")

        // 1. Prepare configuration files
        writeProperties(context)

        // 2. Build IDE command arguments
        val ideArgs = IdeArgBuilder.build(context)
        val ideScript = resolveIdeScript(ideDir)

        log.info("Starting native IDE scan: {} {}", ideScript, ideArgs.joinToString(" "))

        // 3. Execute IDE
        val spec = ProcessSpec(
            command = ideScript.toString(),
            args = ideArgs,
            workDir = context.paths.projectDir,
            timeout = context.runtime.timeout,
            env = context.runtime.envVars,
        )

        val process = processRunner.start(spec)

        // 4. Collect log output
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

        // 5. Determine final exit code (SARIF takes precedence)
        return readIdeExitCode(context.paths.resultsDir, processExitCode)
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    /**
     * Writes `idea.properties` and `idea64.vmoptions` into a config directory
     * so the IDE picks them up via environment variables.
     */
    private fun writeProperties(context: ScanContext) {
        val configDir = context.paths.cacheDir.resolve("idea-config")
        fileSystem.createDirectories(configDir)

        PropertyGenerator.writeTo(context, configDir) { path, content ->
            fileSystem.write(path, content)
        }

        log.debug("Wrote IDE property files to {}", configDir)
    }

    /**
     * Locates the `inspect.sh` (or `inspect.bat` on Windows) script inside the IDE
     * installation directory. Falls back to `qodana.sh`/`qodana.bat` if the
     * standard inspect script is missing.
     */
    private fun resolveIdeScript(ideDir: Path): Path {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val binDir = ideDir.resolve("bin")

        val inspectScript = binDir.resolve(if (isWindows) "inspect.bat" else "inspect.sh")
        if (fileSystem.exists(inspectScript)) return inspectScript

        val qodanaScript = binDir.resolve(if (isWindows) "qodana.bat" else "qodana.sh")
        if (fileSystem.exists(qodanaScript)) return qodanaScript

        throw IllegalStateException(
            "Cannot find inspect script in $binDir. " +
                "Searched: ${inspectScript.fileName}, ${qodanaScript.fileName}"
        )
    }

    /**
     * Tries to read the exit code written by the IDE into `qodana.sarif.json`.
     * Falls back to [processExitCode] when the file is absent or unparseable.
     *
     * The IDE writes a top-level `"exitCode"` property in the SARIF report to
     * communicate its own notion of success/failure (e.g. threshold exceeded).
     */
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

package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.port.ThirdPartyLinter
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class CdnetLinter(
    private val processRunner: ProcessRunner,
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
    // Resolve a command name to its absolute path on PATH, or null. Injectable for tests; the
    // default scans the process PATH (the image installs `inspectcode` to /opt/qodana-cdnet/bin).
    private val pathLookup: (String) -> Path? = ::resolveOnSystemPath,
) : ThirdPartyLinter {
    private val logger = LoggerFactory.getLogger(CdnetLinter::class.java)

    override suspend fun runAnalysis(context: ThirdPartyScanContext) {
        val args = CdnetOptions.computeArgs(context)
        logger.info("Computed cdnet args: {}", args)

        if (NugetConfig.isNeeded()) {
            val homeDir = Path.of(System.getProperty("user.home"))
            logger.info("Preparing NuGet config in {}", homeDir)
            NugetConfig.prepare(homeDir)
        }
        val envOverrides = NugetConfig.unsetVariables()

        terminal.println("Running ReSharper InspectCode...")

        val result =
            processRunner.run(
                ProcessSpec(
                    command = args[0], // the executable; args.drop(1) are its arguments
                    args = args.drop(1),
                    env = envOverrides,
                    workDir = context.paths.projectDir,
                ),
            )

        if (result.exitCode != 0) {
            throw RuntimeException("Analysis exited with code: ${result.exitCode}")
        }

        CdnetSarif.patchReport(
            sarifPath = context.paths.resultsDir.resolve("qodana.sarif.json"),
            logDir = context.logDir,
            fileSystem = fileSystem,
        )
    }

    /**
     * Resolve the ReSharper CLT entrypoint. The image installs `inspectcode` to /opt/qodana-cdnet/bin
     * (on PATH), so PATH discovery wins in the container. Outside the image (dev), fall back to
     * extracting `clt.zip` in [targetPath] (cacheDir/tools) and locating the `inspectcode.sh` launcher.
     */
    override fun mountTools(targetPath: Path): Map<String, Path> {
        // Bare name by design: the image installs the launcher on PATH as `/opt/qodana-cdnet/bin/inspectcode`.
        // The `.sh` variant is only relevant to the dev cache-fallback `findLauncher` below.
        pathLookup("inspectcode")?.let {
            logger.info("Resolved inspectcode on PATH at {}", it)
            return mapOf("clt" to it)
        }

        logger.info("inspectcode not on PATH; resolving CLT from {}", targetPath)
        var launcher = findLauncher(targetPath)
        if (launcher == null) {
            val archive = targetPath.resolve("clt.zip")
            if (Files.exists(archive)) {
                fileSystem.extractArchive(archive, targetPath)
                launcher = findLauncher(targetPath)
            }
        }

        val resolved =
            launcher
                ?: throw IllegalStateException(
                    "ReSharper CLT not found: `inspectcode` is not on PATH and no launcher/clt.zip " +
                        "was found at $targetPath.",
                )

        // A zip/tar entry may not preserve the executable bit, so set it on the cache-resolved launcher.
        if (!isWindows()) {
            resolved.toFile().setExecutable(true)
        }

        return mapOf("clt" to resolved)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun findLauncher(dir: Path): Path? {
        if (!Files.exists(dir)) return null
        return fileSystem.walk(dir, "**/inspectcode.sh").firstOrNull()
            ?: fileSystem.walk(dir, "**/inspectcode").firstOrNull()
    }
}

/** Default PATH scan: first executable regular file `<dir>/<name>` across [pathEnv], else null. */
internal fun resolveOnSystemPath(
    name: String,
    pathEnv: String? = System.getenv("PATH"),
): Path? {
    if (pathEnv == null) return null
    return pathEnv
        .split(File.pathSeparatorChar)
        .asSequence()
        .filter { it.isNotEmpty() }
        .map { Path.of(it).resolve(name) }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

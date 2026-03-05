package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.app.scan.ScanUseCase
import org.jetbrains.qodana.core.env.CiDetector
import org.jetbrains.qodana.core.model.*
import java.nio.file.Path

class ScanCommand(
    private val scanUseCaseFactory: () -> ScanUseCase,
) : CliktCommand("scan") {

    override fun help(context: Context) = "Run Qodana analysis"

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val resultsDir by option("-o", "--results-dir", help = "Directory to save results")
        .path()
        .default(Path.of("./results"))
    private val cacheDir by option("--cache-dir", help = "Directory for caches").path()
    private val reportDir by option("--report-dir", help = "Directory for HTML report").path()

    private val linter by option("-l", "--linter", help = "Linter to use")
    private val ide by option("--ide", help = "Path to local IDE installation").path()

    private val volumes by option("-v", "--volume", help = "Docker volume mount").multiple()
    private val dockerEnv by option("-e", "--env", help = "Docker environment variable").multiple()
    private val user by option("-u", "--user", help = "Docker user")
    private val skipPull by option("--skip-pull", help = "Skip Docker image pull").flag()
    private val memory by option("--memory", help = "Docker memory limit in bytes").long()
    private val cpuLimit by option("--cpu", help = "Docker CPU count").int()

    private val profileName by option("--profile-name", help = "Inspection profile name")
    private val profilePath by option("--profile-path", help = "Inspection profile path")

    private val diffStart by option("--diff-start", help = "Git start revision")
    private val diffEnd by option("--diff-end", help = "Git end revision")
    private val onlyDirectory by option("--only-directory", help = "Only analyze this directory")
    private val fullHistory by option("--full-history", help = "Analyze full git history").flag()

    private val baseline by option("-b", "--baseline", help = "Baseline SARIF file").path()
    private val baselineIncludeAbsent by option("--baseline-include-absent").flag()
    private val failThreshold by option("--fail-threshold", help = "Fail threshold").int()

    private val saveReport by option("--save-report", help = "Save HTML report").flag(default = true)
    private val showReport by option("--show-report", help = "Open report in browser").flag()
    private val codeClimate by option("--code-climate", help = "Generate Code Climate report").flag()

    private val applyFixes by option("--apply-fixes", help = "Apply code fixes").flag()
    private val cleanup by option("--cleanup", help = "Run code cleanup").flag()
    private val fixesStrategy by option("--fixes-strategy", help = "Fixes strategy")
    private val script by option("--script", help = "Analysis script name")

    private val property by option("--property", "-P", help = "JVM property (key=value)").multiple()
    private val analysisId by option("--analysis-id", help = "Analysis ID")
    private val coverageDir by option("--coverage-dir", help = "Coverage data directory").path()
    private val globalConfigDir by option("--global-config-dir", help = "Global config directory").path()
    private val jvmDebugPort by option("--jvm-debug-port", help = "JVM debug port").int()
    private val disableStatistics by option("--disable-statistics").flag()
    private val clearCache by option("--clear-cache").flag()

    override fun run() {
        val outputFormats = buildSet {
            add(ReportOptions.OutputFormat.SARIF)
            if (codeClimate) add(ReportOptions.OutputFormat.CODE_CLIMATE)
        }

        val properties = property.associate { prop ->
            val parts = prop.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }

        val envVars = dockerEnv.associate { env ->
            val parts = env.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }

        val actualResultsDir = resultsDir
        val actualReportDir = reportDir ?: actualResultsDir.resolve("report")
        val actualCacheDir = cacheDir ?: Path.of(System.getProperty("user.home"), ".cache", "qodana")

        val context = ScanContext(
            paths = ScanPaths(
                projectDir = projectDir.toAbsolutePath(),
                resultsDir = actualResultsDir.toAbsolutePath(),
                cacheDir = actualCacheDir.toAbsolutePath(),
                reportDir = actualReportDir.toAbsolutePath(),
            ),
            auth = AuthContext(
                token = System.getenv("QODANA_TOKEN"),
                endpoint = System.getenv("QODANA_ENDPOINT") ?: "https://qodana.cloud",
                licenseOnlyToken = System.getenv("QODANA_LICENSE_ONLY_TOKEN"),
            ),
            runtime = RuntimeContext(
                ideDir = ide,
                failThreshold = failThreshold,
                properties = properties,
                disableStatistics = disableStatistics,
                forceFullHistory = fullHistory,
                clearCache = clearCache,
                analysisId = analysisId,
                jvmDebugPort = jvmDebugPort,
                applyFixes = applyFixes,
                cleanup = cleanup,
                fixesStrategy = fixesStrategy,
                script = script ?: "default",
                diffStart = diffStart,
                diffEnd = diffEnd,
                onlyDirectory = onlyDirectory,
                coverageDir = coverageDir,
                globalConfigDir = globalConfigDir,
            ),
            ci = CiDetector.detect() ?: CiContext(),
            report = ReportOptions(
                outputFormats = outputFormats,
                baselinePath = baseline,
                baselineIncludeAbsent = baselineIncludeAbsent,
                saveReport = saveReport,
                showReport = showReport,
            ),
            docker = DockerOptions(
                image = linter,
                volumes = volumes,
                envVars = envVars,
                user = user,
                memoryLimit = memory,
                cpuLimit = cpuLimit,
                skipPull = skipPull,
            ),
            linter = linter,
            profile = if (profileName != null || profilePath != null) {
                ProfileSpec(name = profileName, path = profilePath)
            } else null,
            nativeMode = ide != null,
        )

        val scanUseCase = scanUseCaseFactory()
        val exitCode = runBlocking { scanUseCase.run(context) }
        throw ProgramResult(exitCode)
    }
}

package org.jetbrains.qodana.cli.command

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.QodanaYaml
import org.jetbrains.qodana.core.model.ScanPaths
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.core.terminal.MordantTerminal
import org.jetbrains.qodana.engine.env.CiDetector
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.engine.scan.ReportPort
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

fun interface ScanReportDisplay {
    fun show(resultsDir: Path, reportDir: Path, port: Int): Int
}

class ScanCommand(
    private val terminal: Terminal = MordantTerminal(),
    private val scanReportDisplay: ScanReportDisplay? = null,
    private val scanRunner: suspend (ScanContext) -> Int,
) : CliktCommand("scan") {

    override fun help(context: Context) = "Scan project with Qodana"

    private val linter by option("-l", "--linter", help = "Defines the linter to be used for analysis")
    private val withinDocker by option("--within-docker", help = "Set true/false for container mode")
    private val image by option("--image", help = "Defines image to use for analysis execution")
    private val ide by option("--ide", help = "Run Qodana without a container")

    private val projectDir by option("-i", "--project-dir", help = "Root directory of the inspected project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val repositoryRoot by option("--repository-root", help = "Path to repository root")
        .path(mustExist = true)
    private val resultsDir by option("-o", "--results-dir", help = "Override results directory")
        .path()
    private val cacheDir by option("--cache-dir", help = "Override cache directory")
        .path()
    private val reportDir by option("-r", "--report-dir", help = "Override report directory")
        .path()

    private val printProblems by option("--print-problems", help = "Print all found problems in CLI output")
        .flag()
    private val codeClimate by option("--code-climate", help = "Generate Code Climate report")
        .flag(default = CiDetector.isGitLab())
    private val bitbucketInsights by option("--bitbucket-insights", "--code-insights", help = "Send BitBucket Code Insights")
        .flag(default = CiDetector.isBitBucket())
    private val clearCache by option("--clear-cache", help = "Clear local Qodana cache before analysis")
        .flag()
    private val showReport by option("-w", "--show-report", help = "Serve HTML report")
        .flag()
    private val port by option("--port", help = "Deprecated, use --show-report-port")
        .int()
    private val showReportPort by option("--show-report-port", help = "Port to serve report")
        .int()

    private val configName by option("--config", help = "Custom configuration file instead of qodana.yaml")

    private val analysisId by option("-a", "--analysis-id", help = "Unique report identifier")
        .default(UUID.randomUUID().toString())
    private val baseline by option("-b", "--baseline", help = "Path to SARIF baseline")
        .path()
    private val baselineIncludeAbsent by option("--baseline-include-absent", help = "Include absent baseline results")
        .flag()
    private val fullHistory by option("--full-history", help = "Run analysis through full commit history")
        .flag()
    private val commit by option("--commit", help = "Base commit for incremental analysis")
    private val failThreshold by option("--fail-threshold", help = "Problems threshold to fail the run")
        .int()

    private val disableSanity by option("--disable-sanity", help = "Skip sanity profile inspections")
        .flag()
    private val sourceDirectory by option("--source-directory", help = "Deprecated, use --only-directory")
    private val onlyDirectory by option("-d", "--only-directory", help = "Directory inside project to inspect")

    private val profileName by option("-n", "--profile-name", help = "Profile name")
    private val profilePath by option("-p", "--profile-path", help = "Path to profile file")
    private val runPromo by option("--run-promo", help = "Enable/disable promo profile")
    private val script by option("--script", help = "Override run scenario")
        .default("default")
    private val coverageDir by option("--coverage-dir", help = "Directory with coverage data")
        .path()

    private val applyFixes by option("--apply-fixes", help = "Apply quick-fixes")
        .flag()
    private val cleanup by option("--cleanup", help = "Run project cleanup")
        .flag()
    private val fixesStrategy by option("--fixes-strategy", help = "Deprecated, use --apply-fixes/--cleanup")

    private val property by option("--property", help = "JVM property in key=value format")
        .multiple()
    private val saveReport by option("-s", "--save-report", help = "Generate HTML report")
        .flag(default = true)

    private val timeoutMs by option("--timeout", help = "Analysis timeout in milliseconds")
        .long()
        .default(-1L)
    private val timeoutExitCode by option("--timeout-exit-code", help = "Exit code used when timeout is reached")
        .int()
        .default(1)

    private val diffStart by option("--diff-start", help = "Commit to start diff run from")
    private val diffEnd by option("--diff-end", help = "Commit to end diff run on")
    private val forceLocalChangesScript by option("--force-local-changes-script", help = "Force local-changes scenario")
        .flag()
    private val reverse by option("--reverse", help = "Force reverse scoped analysis")
        .flag()

    private val jvmDebugPort by option("--jvm-debug-port", help = "Enable JVM remote debug")
        .int()

    private val noStatistics by option("--no-statistics", help = "Disable anonymous statistics")
        .flag()
    private val clangCompileCommands by option("--compile-commands", help = "Path to compile_commands.json")
        .default("./build/compile_commands.json")
    private val clangArgs by option("--clang-args", help = "Additional clang arguments")
    private val cdnetSolution by option("--solution", help = "Relative path to .sln file")
    private val cdnetProject by option("--project", help = "Relative path to project file")
    private val cdnetConfiguration by option("--configuration", help = "Build configuration")
    private val cdnetPlatform by option("--platform", help = "Build platform")
    private val cdnetNoBuild by option("--no-build", help = "Skip build for cdnet")
        .flag()

    private val env by option("-e", "--env", help = "Additional env variables for container runs")
        .multiple()
    private val volumes by option("-v", "--volume", help = "Additional volumes for container runs")
        .multiple()
    private val user by option("-u", "--user", help = "Override user inside container")
        .default("auto")
    private val skipPull by option("--skip-pull", help = "Skip pulling linter container")
        .flag()

    private val globalConfigDir by option("--global-config-dir", help = "Path to global config dir")
        .path(mustExist = true)
    private val globalConfigId by option("--global-config-id", help = "Global configuration ID")

    override fun run() {
        validateFlags()
        if (forceLocalChangesScript || script == "local-changes") {
            echo(
                "Warning: Using local-changes script is deprecated, please switch to other mechanisms of incremental analysis. " +
                    "Further information - https://www.jetbrains.com/help/qodana/analyze-pr.html"
            )
        }

        val absProjectDir = projectDir.toAbsolutePath().normalize()
        validateProjectDir(absProjectDir)

        val yaml = loadQodanaYaml(absProjectDir, configName)
        val analyzer = resolveAnalyzer(yaml, absProjectDir)

        val startHash = resolveStartHash(commit, diffStart)
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = fullHistory,
            script = effectiveScript(),
            startHash = startHash,
            forceLocalChanges = forceLocalChangesScript,
            isContainer = analyzer.analysisMode == AnalysisMode.CONTAINER,
            reversePrAnalysis = reverse,
        )

        val resolvedRepositoryRoot = repositoryRoot?.toAbsolutePath()?.normalize()
            ?: detectRepositoryRoot(absProjectDir)
        val paths = resolvePaths(
            projectDir = absProjectDir,
            repositoryRoot = resolvedRepositoryRoot,
            linterName = analyzer.linterName ?: analyzer.image ?: "qodana",
            isContainer = CiDetector.isContainer(),
        )
        val effectiveConfigDir = prepareEffectiveConfigDir(
            analysisMode = analyzer.analysisMode,
            projectDir = absProjectDir,
            cacheDir = paths.cacheDir,
            customConfigName = configName,
        )

        val outputFormats = buildSet {
            add(ReportOptions.OutputFormat.SARIF)
            if (codeClimate) add(ReportOptions.OutputFormat.CODE_CLIMATE)
            if (bitbucketInsights) add(ReportOptions.OutputFormat.BITBUCKET)
        }
        val (parsedProperties, parsedPropertyFlags) = ScanContextUtils.parsePropertiesAndFlags(property)

        val context = ScanContext(
            paths = paths,
            auth = AuthContext(
                token = System.getenv(QodanaEnv.TOKEN),
                endpoint = System.getenv(QodanaEnv.ENDPOINT) ?: QodanaEnv.DEFAULT_ENDPOINT,
                licenseOnlyToken = System.getenv(QodanaEnv.LICENSE_ONLY_TOKEN),
            ),
            runtime = RuntimeContext(
                ideDir = analyzer.ideDir,
                timeout = ScanContextUtils.getAnalysisTimeout(timeoutMs),
                timeoutExitCode = timeoutExitCode,
                envVars = parseKeyValueOptions(env),
                properties = parsedProperties,
                propertyFlags = parsedPropertyFlags,
                failThreshold = failThreshold,
                disableStatistics = noStatistics,
                noStatistics = noStatistics,
                forceFullHistory = fullHistory,
                clearCache = clearCache,
                analysisId = analysisId,
                jvmDebugPort = jvmDebugPort,
                applyFixes = applyFixes,
                cleanup = cleanup,
                fixesStrategy = fixesStrategy,
                disableSanity = disableSanity,
                runPromo = runPromo,
                script = effectiveScript(),
                commit = commit,
                diffStart = startHash,
                diffEnd = diffEnd,
                forceLocalChangesScript = forceLocalChangesScript,
                reversePrAnalysis = reverse,
                onlyDirectory = onlyDirectory ?: sourceDirectory,
                coverageDir = coverageDir,
                globalConfigDir = globalConfigDir,
                globalConfigId = globalConfigId,
                effectiveConfigDir = effectiveConfigDir,
                customConfigName = configName,
                showReportPort = showReportPort,
                deprecatedPort = port,
                clangCompileCommands = clangCompileCommands,
                clangArgs = clangArgs,
                cdnetSolution = cdnetSolution,
                cdnetProject = cdnetProject,
                cdnetConfiguration = cdnetConfiguration,
                cdnetPlatform = cdnetPlatform,
                cdnetNoBuild = cdnetNoBuild,
            ),
            ci = CiDetector.extractQodanaEnvironment(CiDetector.detect()),
            report = ReportOptions(
                outputFormats = outputFormats,
                baselinePath = baseline,
                baselineIncludeAbsent = baselineIncludeAbsent,
                saveReport = saveReport,
                showReport = showReport,
                printProblems = printProblems,
            ),
            docker = DockerOptions(
                image = analyzer.image,
                volumes = volumes,
                envVars = parseKeyValueOptions(env),
                user = if (user == "auto") null else user,
                skipPull = skipPull,
            ),
            linter = analyzer.linterName,
            profile = ProfileSpec(name = profileName, path = profilePath),
            yaml = yaml,
            scenario = scenario,
            nativeMode = analyzer.analysisMode == AnalysisMode.NATIVE,
            analysisMode = analyzer.analysisMode,
        )

        var exitCode = runBlocking { scanRunner(context) }
        if (showReport) {
            val reportDisplay = scanReportDisplay ?: ScanReportDisplay { resultsDir, reportDir, port ->
                ReportDisplay.showReport(
                    terminal = terminal,
                    resultsDir = resultsDir,
                    reportDir = reportDir,
                    port = port,
                )
            }
            val resolvedPort = ReportPort.getShowReportPort(showReportPort = showReportPort, port = port)
            val showReportExitCode = reportDisplay.show(
                resultsDir = context.paths.resultsDir,
                reportDir = context.paths.reportDir,
                port = resolvedPort,
            )
            if (exitCode == 0 && showReportExitCode != 0) {
                terminal.error("Failed to show report")
                exitCode = showReportExitCode
            }
        }
        throw ProgramResult(exitCode)
    }

    private fun validateFlags() {
        if (applyFixes && cleanup) {
            throw UsageError("Options --apply-fixes and --cleanup are mutually exclusive")
        }
        if (profileName != null && profilePath != null) {
            throw UsageError("Options --profile-name and --profile-path are mutually exclusive")
        }
        if (sourceDirectory != null && onlyDirectory != null) {
            throw UsageError("Options --source-directory and --only-directory are mutually exclusive")
        }
        if (script != "default" && (forceLocalChangesScript || fullHistory)) {
            throw UsageError("Options --script, --force-local-changes-script and --full-history are mutually exclusive")
        }
        if (commit != null && script != "default") {
            throw UsageError("Options --commit and --script are mutually exclusive")
        }
        if (commit != null && diffStart != null) {
            throw UsageError("Options --commit and --diff-start are mutually exclusive")
        }
        if (script != "default" && diffStart != null) {
            throw UsageError("Options --script and --diff-start are mutually exclusive")
        }
        if ((globalConfigDir == null) xor (globalConfigId == null)) {
            throw UsageError("--global-config-dir and --global-config-id must be specified together")
        }
        if (linter != null && ide != null) {
            throw UsageError("Options --linter and --ide are mutually exclusive")
        }
        if (image != null && ide != null) {
            throw UsageError("Options --image and --ide are mutually exclusive")
        }
        if (ide != null && skipPull) {
            throw UsageError("Options --skip-pull and --ide are mutually exclusive")
        }
        if (ide != null && volumes.isNotEmpty()) {
            throw UsageError("Options --volume and --ide are mutually exclusive")
        }
        if (ide != null && env.isNotEmpty()) {
            throw UsageError("Options --env and --ide are mutually exclusive")
        }
        if (ide != null && user != "auto") {
            throw UsageError("Options --user and --ide are mutually exclusive")
        }
        val wd = withinDocker?.trim()?.lowercase()
        if (wd != null && wd.isNotEmpty() && wd != "true" && wd != "false") {
            throw UsageError("Wrong value for --within-docker: $withinDocker. Use true/false")
        }
    }

    private fun validateProjectDir(dir: Path) {
        if (org.jetbrains.qodana.engine.scan.SystemUtils.isHomeDirectory(dir.toString())) {
            echo("Warning: Project directory ($dir) is the home directory")
        }
        val hasFiles = Files.walk(dir).use { stream ->
            stream.anyMatch { it != dir && Files.isRegularFile(it) }
        }
        if (!hasFiles) {
            throw UsageError("No files to check with Qodana found in $dir")
        }
    }

    private fun effectiveScript(): String {
        return script
    }

    private fun resolveStartHash(commit: String?, diffStart: String?): String? {
        return when {
            commit == diffStart -> commit
            commit == null -> diffStart
            diffStart == null -> commit
            else -> throw UsageError("Conflicting CLI arguments: --commit=$commit --diff-start=$diffStart")
        }
    }

    private fun resolveAnalyzer(yaml: QodanaYaml?, projectDir: Path): AnalyzerResolution {
        val explicitWithinDocker = parseWithinDocker(withinDocker)
        val yamlWithinDocker = parseWithinDocker(yaml?.withinDocker)

        ide?.let { ideValue ->
            val linterByCode = Linters.findByProductCode(ideValue.removeSuffix(Linters.EAP_SUFFIX))
            if (linterByCode != null) {
                return AnalyzerResolution(
                    analysisMode = AnalysisMode.NATIVE,
                    linterName = linter ?: linterByCode.name,
                    image = null,
                    ideDir = null,
                )
            }
            val idePath = Path.of(ideValue)
            if (Files.isDirectory(idePath)) {
                return AnalyzerResolution(
                    analysisMode = AnalysisMode.NATIVE,
                    linterName = linter,
                    image = null,
                    ideDir = idePath.toAbsolutePath().normalize(),
                )
            }
            throw UsageError("--ide value '$ideValue' is not a valid product code or existing directory")
        }

        image?.let { imageValue ->
            val linterByImage = Linters.findByDockerImage(imageValue)
            return AnalyzerResolution(
                analysisMode = AnalysisMode.CONTAINER,
                linterName = linter ?: linterByImage?.name ?: imageValue,
                image = imageValue,
                ideDir = null,
            )
        }

        val yamlImage = yaml?.image
        val yamlLinter = yaml?.linter

        val chosenLinter = linter ?: yamlLinter
        if (chosenLinter != null) {
            // Legacy compatibility: image could be passed via --linter
            if (chosenLinter.contains("/") && Linters.findByName(chosenLinter) == null) {
                if (explicitWithinDocker == false || yamlWithinDocker == false) {
                    throw UsageError("Image-like --linter value requires container mode")
                }
                return AnalyzerResolution(
                    analysisMode = AnalysisMode.CONTAINER,
                    linterName = Linters.findByDockerImage(chosenLinter)?.name ?: chosenLinter,
                    image = chosenLinter,
                    ideDir = null,
                )
            }

            val resolved = Linters.findByName(chosenLinter)
            val useContainer = explicitWithinDocker ?: yamlWithinDocker ?: true
            if (useContainer) {
                return AnalyzerResolution(
                    analysisMode = AnalysisMode.CONTAINER,
                    linterName = resolved?.name ?: chosenLinter,
                    image = yamlImage ?: resolved?.image() ?: chosenLinter,
                    ideDir = null,
                )
            }
            return AnalyzerResolution(
                analysisMode = AnalysisMode.NATIVE,
                linterName = resolved?.name ?: chosenLinter,
                image = null,
                ideDir = null,
            )
        }

        if (!yamlImage.isNullOrBlank()) {
            val linterByImage = Linters.findByDockerImage(yamlImage)
            return AnalyzerResolution(
                analysisMode = AnalysisMode.CONTAINER,
                linterName = linterByImage?.name ?: yamlImage,
                image = yamlImage,
                ideDir = null,
            )
        }

        val detectedLinter = org.jetbrains.qodana.engine.scan.ProjectDetector.detectLinter(projectDir)
        if (detectedLinter != null) {
            return AnalyzerResolution(
                analysisMode = AnalysisMode.CONTAINER,
                linterName = detectedLinter.name,
                image = detectedLinter.image(),
                ideDir = null,
            )
        }

        return AnalyzerResolution(
            analysisMode = AnalysisMode.CONTAINER,
            linterName = Linters.JVM.name,
            image = Linters.JVM.image(),
            ideDir = null,
        )
    }

    private fun resolvePaths(
        projectDir: Path,
        repositoryRoot: Path,
        linterName: String,
        isContainer: Boolean,
    ): ScanPaths {
        val defaultPaths = if (isContainer) {
            Triple(Path.of("/data/results"), Path.of("/data/cache"), Path.of("/data/results/report"))
        } else {
            val linterDir = qodanaSystemDir().resolve(computeScanId(linterName, projectDir))
            val defaultResults = linterDir.resolve("results")
            val defaultCache = linterDir.resolve("cache")
            val defaultReport = defaultResults.resolve("report")
            Triple(defaultResults, defaultCache, defaultReport)
        }

        val actualResultsDir = resultsDir?.toAbsolutePath()?.normalize() ?: defaultPaths.first
        val actualCacheDir = cacheDir?.toAbsolutePath()?.normalize() ?: defaultPaths.second
        val actualReportDir = reportDir?.toAbsolutePath()?.normalize() ?: defaultPaths.third

        return ScanPaths(
            projectDir = projectDir,
            repositoryRoot = repositoryRoot,
            resultsDir = actualResultsDir,
            cacheDir = actualCacheDir,
            reportDir = actualReportDir,
        )
    }

    private fun qodanaSystemDir(): Path {
        val home = System.getProperty("user.home") ?: "."
        val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
        val userCache = if (isMac) {
            Path.of(home, "Library", "Caches")
        } else {
            Path.of(home, ".cache")
        }
        return userCache.resolve("JetBrains").resolve("Qodana")
    }

    private fun computeScanId(linterName: String, projectDir: Path): String {
        val linterHash = sha256(linterName).take(8)
        val projectHash = sha256(projectDir.toString()).take(8)
        return "$linterHash-$projectHash"
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun parseWithinDocker(value: String?): Boolean? {
        val normalized = value?.trim()?.lowercase() ?: return null
        if (normalized.isEmpty()) return null
        return when (normalized) {
            "true" -> true
            "false" -> false
            else -> throw UsageError("Wrong value for --within-docker: $value. Use true/false")
        }
    }

    private fun parseKeyValueOptions(values: List<String>): Map<String, String> {
        return values.associate { entry ->
            val parts = entry.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
    }

    private fun loadQodanaYaml(projectDir: Path, customConfigName: String?): QodanaYaml? {
        val yamlPath = resolveQodanaYamlPath(projectDir, customConfigName) ?: return null

        return try {
            YAMLMapper.builder()
                .addModule(kotlinModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build()
                .readValue(yamlPath.toFile(), QodanaYaml::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveQodanaYamlPath(projectDir: Path, customConfigName: String?): Path? {
        return when {
            !customConfigName.isNullOrBlank() -> projectDir.resolve(customConfigName)
            Files.exists(projectDir.resolve("qodana.yaml")) -> projectDir.resolve("qodana.yaml")
            Files.exists(projectDir.resolve("qodana.yml")) -> projectDir.resolve("qodana.yml")
            else -> null
        }
    }

    private fun prepareEffectiveConfigDir(
        analysisMode: AnalysisMode,
        projectDir: Path,
        cacheDir: Path,
        customConfigName: String?,
    ): Path? {
        if (analysisMode != AnalysisMode.NATIVE) return null

        val effectiveDir = cacheDir.resolve("effective-config")
        runCatching {
            Files.createDirectories(effectiveDir)
            val yamlPath = resolveQodanaYamlPath(projectDir, customConfigName)
            if (yamlPath != null && Files.exists(yamlPath) && Files.isRegularFile(yamlPath)) {
                val targetYaml = effectiveDir.resolve("qodana.yaml")
                if (yamlPath.toAbsolutePath().normalize() != targetYaml.toAbsolutePath().normalize()) {
                    Files.copy(yamlPath, targetYaml, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }.onFailure { error ->
            terminal.warn("Failed to prepare effective configuration directory: ${error.message}")
        }
        return effectiveDir
    }

    private fun detectRepositoryRoot(projectDir: Path): Path {
        return runCatching {
            val process = ProcessBuilder("git", "-C", projectDir.toString(), "rev-parse", "--show-toplevel")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotEmpty()) {
                Path.of(output).toAbsolutePath().normalize()
            } else {
                projectDir
            }
        }.getOrElse { projectDir }
    }

    private data class AnalyzerResolution(
        val analysisMode: AnalysisMode,
        val linterName: String?,
        val image: String?,
        val ideDir: Path?,
    )
}

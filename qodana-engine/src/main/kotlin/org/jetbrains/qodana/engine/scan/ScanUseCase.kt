package org.jetbrains.qodana.engine.scan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.engine.cloud.LicenseSetup
import org.jetbrains.qodana.engine.cloud.LicenseToken
import org.jetbrains.qodana.engine.cloud.LicenseValidator
import org.jetbrains.qodana.engine.config.EffectiveConfig
import org.jetbrains.qodana.engine.fs.FileUtils
import org.jetbrains.qodana.engine.model.ReportOptions
import org.jetbrains.qodana.engine.model.RunScenario
import org.jetbrains.qodana.engine.model.ScanContext
import org.jetbrains.qodana.engine.model.ScanContextUtils
import org.jetbrains.qodana.engine.port.GitClient
import org.jetbrains.qodana.engine.report.BitBucketExporter
import org.jetbrains.qodana.engine.report.CodeClimateExporter
import org.jetbrains.qodana.engine.report.ReportProcessor
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.report.SarifUtils
import org.jetbrains.qodana.engine.report.SarifVersioning
import org.jetbrains.qodana.engine.startup.DeviceId
import org.jetbrains.qodana.engine.startup.PrepareHost
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class ScanUseCase(
    private val prepareHost: PrepareHost,
    private val nativeScan: NativeScan,
    private val containerScan: ContainerScan,
    private val reportProcessor: ReportProcessor,
    private val reportPublisher: ReportPublishUseCase?,
    private val licenseValidator: LicenseValidator?,
    private val codeClimateExporter: CodeClimateExporter?,
    private val bitBucketExporter: BitBucketExporter?,
    private val gitClient: GitClient?,
    private val terminal: Terminal,
) {
    private val log = LoggerFactory.getLogger(ScanUseCase::class.java)
    private val pipelineFactory =
        ScanExecutionPipelineFactory(
            nativePipeline = NativeScanExecutionPipeline(prepareHost, nativeScan),
            inDockerPipeline = InDockerScanExecutionPipeline(prepareHost, containerScan),
            dockerLauncherPipeline = DockerLauncherScanExecutionPipeline(prepareHost, containerScan),
        )

    suspend fun run(context: ScanContext): Int {
        val yaml =
            context.yaml ?: EffectiveConfig.load(
                projectDir = context.paths.projectDir,
                customConfigName = context.runtime.customConfigName,
            )
        val effectiveContext = EffectiveConfig.merge(yaml, context)

        val pipeline = pipelineFactory.create(effectiveContext.executionProfile)
        val prepared = pipeline.prepare(effectiveContext)
        val effectiveContextWithIde =
            if (prepared.ideDir != null && effectiveContext.runtime.ideDir == null) {
                effectiveContext.copy(runtime = effectiveContext.runtime.copy(ideDir = prepared.ideDir))
            } else {
                effectiveContext
            }

        val licenseEnv = mutableMapOf<String, String>()
        val linter = effectiveContextWithIde.linter?.let { Linters.findByName(it) }
        if (linter != null && licenseValidator != null) {
            val setup =
                LicenseSetup.setupLicenseAndProjectHash(
                    linter = linter,
                    licenseToken =
                        LicenseToken.resolve(
                            cloudToken = effectiveContextWithIde.auth.token,
                            licenseOnlyToken = effectiveContextWithIde.auth.licenseOnlyToken,
                        ),
                    validator = licenseValidator,
                    existingLicense = System.getenv(QodanaEnv.LICENSE),
                )
            setup.onSuccess { r ->
                // The Qodana dist reads the license + project/org hashes ONLY from the analyzer's env
                // vars; propagate them via the analysis env (NativeScan forwards runtime.envVars to the
                // IDE). Previously the key was discarded and the hashes written via System.setProperty,
                // which the dist never reads — so no paid linter ever licensed. Don't clobber a license
                // supplied via --env (LicenseSetup already honours a pre-set QODANA_LICENSE process env).
                if (r.licenseKey.isNotBlank() &&
                    !effectiveContextWithIde.runtime.envVars.containsKey(QodanaEnv.LICENSE)
                ) {
                    licenseEnv[QodanaEnv.LICENSE] = r.licenseKey
                }
                if (r.projectIdHash.isNotBlank()) licenseEnv[QodanaEnv.PROJECT_ID_HASH] = r.projectIdHash
                if (r.organisationIdHash.isNotBlank()) licenseEnv[QodanaEnv.ORGANISATION_ID_HASH] = r.organisationIdHash
            }
            setup.onFailure { e ->
                // warn (not fail): preserves today's QDJVM/QDPY Community-EAP behavior while surfacing a
                // clear cause (e.g. "Community plan does not support paid linters") instead of the dist's
                // cryptic "No valid license found" crash.
                terminal.warn("License setup failed: ${e.message}")
            }
        }

        val dotnetConfig = EffectiveConfig.resolveDotNet(yaml)
        if (dotnetConfig != null) {
            log.info("DotNet configuration: solution={}, project={}", dotnetConfig.solution, dotnetConfig.project)
        }

        val contextForAnalysis =
            prepareScenarioContext(effectiveContextWithIde).let { ctx ->
                if (licenseEnv.isEmpty()) {
                    ctx
                } else {
                    ctx.copy(runtime = ctx.runtime.copy(envVars = ctx.runtime.envVars + licenseEnv))
                }
            }
        val bootstrapExitCode = runBootstrapIfNeeded(contextForAnalysis, pipeline)
        if (bootstrapExitCode != 0) {
            return bootstrapExitCode
        }
        validateGit(contextForAnalysis)

        val exitCode = runAnalysis(contextForAnalysis)
        enrichSarifMetadata(contextForAnalysis)
        handleSpecialExitCode(exitCode, contextForAnalysis)?.let { mapped ->
            return mapped
        }
        if (exitCode != ExitCode.SUCCESS.code && exitCode != ExitCode.FAIL_THRESHOLD.code) {
            return exitCode
        }

        val processedReport =
            reportProcessor.process(
                resultsDir = contextForAnalysis.paths.resultsDir,
                reportDir = contextForAnalysis.paths.reportDir,
                reportOptions = contextForAnalysis.report,
                failThreshold = contextForAnalysis.runtime.failThreshold,
                analysisExitCode = exitCode,
            )

        if (contextForAnalysis.report.printProblems) {
            reportProcessor.formatProblems(contextForAnalysis.paths.resultsDir).forEach { line ->
                terminal.println("  $line")
            }
        }

        if (contextForAnalysis.report.outputFormats.contains(ReportOptions.OutputFormat.CODE_CLIMATE)) {
            codeClimateExporter?.export(contextForAnalysis.paths.resultsDir)
        }
        if (contextForAnalysis.report.outputFormats.contains(ReportOptions.OutputFormat.BITBUCKET)) {
            exportBitBucket(contextForAnalysis)
        }

        terminal.println(
            "Analysis complete: ${processedReport.totalProblems} problems found" +
                (processedReport.baselineResult?.let { " (${it.newCount} new)" } ?: ""),
        )

        if (contextForAnalysis.auth.hasToken && !contextForAnalysis.auth.isLicenseOnly && reportPublisher != null) {
            val publishResult =
                reportPublisher.publish(
                    analysisId = contextForAnalysis.runtime.analysisId ?: "",
                    reportPath = contextForAnalysis.paths.resultsDir,
                    auth = contextForAnalysis.auth,
                )
            publishResult.onSuccess { result ->
                if (result.success) {
                    terminal.println("Report published: ${result.url}")
                }
            }
        }

        return processedReport.exitCode
    }

    private fun handleSpecialExitCode(
        exitCode: Int,
        context: ScanContext,
    ): Int? =
        when (exitCode) {
            TIMEOUT_EXIT_CODE_PLACEHOLDER -> {
                terminal.error("Qodana analysis reached timeout ${context.runtime.timeout}")
                context.runtime.timeoutExitCode
            }
            EMPTY_CHANGESET_EXIT_CODE_PLACEHOLDER -> {
                terminal.error("Nothing to analyse. Exiting with 0")
                0
            }
            else -> null
        }

    private suspend fun exportBitBucket(context: ScanContext) {
        val exporter = bitBucketExporter ?: return
        val workspace = System.getenv("BITBUCKET_WORKSPACE") ?: return
        val repoSlug = System.getenv("BITBUCKET_REPO_SLUG") ?: return
        val commitHash = System.getenv("BITBUCKET_COMMIT") ?: return
        val token =
            System.getenv("QD_BITBUCKET_TOKEN")
                ?: System.getenv("QODANA_TOKEN")
                ?: return
        val bitbucketUrl =
            System.getenv("QD_BITBUCKET_URL")
                ?: System.getenv("BITBUCKET_GIT_HTTP_ORIGIN")
                ?: "https://api.bitbucket.org"

        runCatching {
            exporter.export(
                resultsDir = context.paths.resultsDir,
                bitbucketUrl = bitbucketUrl,
                workspace = workspace,
                repoSlug = repoSlug,
                commitHash = commitHash,
                token = token,
            )
        }.onFailure { error ->
            terminal.warn("Failed to export BitBucket insights: ${error.message}")
        }
    }

    private fun runBootstrapIfNeeded(
        context: ScanContext,
        pipeline: BaseScanExecutionPipeline,
    ): Int {
        if (!pipeline.canRunBootstrap(context)) {
            return 0
        }
        if (ScanContextUtils.isScopedScenario(context.scenario)) {
            return 0
        }
        val bootstrap = EffectiveConfig.resolveBootstrap(context.yaml) ?: context.runtime.bootstrap
        if (bootstrap.isNullOrBlank()) {
            return 0
        }
        terminal.println("Running bootstrap: $bootstrap")
        val exitCode = Bootstrap.execute(bootstrap, context.paths.projectDir)
        if (exitCode != 0) {
            terminal.error("Bootstrap failed with exit code $exitCode")
        }
        return exitCode
    }

    private suspend fun enrichSarifMetadata(context: ScanContext) {
        val sarifPath = context.paths.resultsDir.resolve(SARIF_FILENAME)
        if (!Files.exists(sarifPath)) return

        val root =
            runCatching {
                JSON_MAPPER.readTree(Files.readString(sarifPath))
            }.getOrElse { error ->
                terminal.warn("Failed to read SARIF for metadata enrichment: ${error.message}")
                return
            }

        val runs = root.path("runs")
        if (!runs.isArray || runs.size() == 0) return
        val runNode = runs[0] as? ObjectNode ?: return

        val versionDetails =
            runCatching {
                SarifVersioning(gitClient).getVersionDetails(
                    projectDir = context.paths.projectDir,
                    envRemoteUrl = context.ci.remoteUrl,
                    envBranch = context.ci.branch,
                    envRevision = context.ci.revision,
                )
            }.getOrElse { error ->
                terminal.debug("Failed to resolve version control details: ${error.message}")
                org.jetbrains.qodana.engine.report
                    .VersionControlDetails()
            }

        val remoteUrl = versionDetails.repositoryUri.ifBlank { context.ci.remoteUrl.orEmpty() }
        val deviceId = DeviceId.getDeviceIdSalt(remoteUrl = remoteUrl).deviceId
        val runProperties =
            (runNode.get("properties") as? ObjectNode) ?: JSON_MAPPER.createObjectNode().also {
                runNode.set<ObjectNode>("properties", it)
            }
        runProperties.put("deviceId", deviceId)

        if (versionDetails.repositoryUri.isNotBlank() ||
            versionDetails.branch.isNotBlank() ||
            versionDetails.revisionId.isNotBlank()
        ) {
            val versionNode =
                JSON_MAPPER.createObjectNode().apply {
                    put("repositoryUri", versionDetails.repositoryUri)
                    put("branch", versionDetails.branch)
                    put("revisionId", versionDetails.revisionId)
                    set<ObjectNode>(
                        "properties",
                        JSON_MAPPER.createObjectNode().apply {
                            put("repoUrl", versionDetails.repositoryUri)
                            put("vcsType", "Git")
                            put("lastAuthorName", versionDetails.lastAuthorName)
                            put("lastAuthorEmail", versionDetails.lastAuthorEmail)
                        },
                    )
                }
            val provenance = JSON_MAPPER.createArrayNode()
            provenance.add(versionNode)
            runNode.set<ObjectNode>("versionControlProvenance", provenance)
        }

        val automationNode = (runNode.get("automationDetails") as? ObjectNode) ?: JSON_MAPPER.createObjectNode()
        if (automationNode.path("guid").asText().isBlank()) {
            automationNode.put("guid", SarifUtils.runGuid())
        }
        if (automationNode.path("id").asText().isBlank()) {
            val productCode =
                context.linter
                    ?.let { Linters.findByName(it)?.productCode }
                    ?: context.linter
                    ?: "qodana"
            automationNode.put("id", SarifUtils.reportId(productCode))
        }
        val automationProperties = (automationNode.get("properties") as? ObjectNode) ?: JSON_MAPPER.createObjectNode()
        val jobUrl = context.ci.jobUrl ?: SarifUtils.jobUrl()
        if (jobUrl.isNotBlank()) {
            automationProperties.put("jobUrl", jobUrl)
        }
        if (automationProperties.size() > 0) {
            automationNode.set<ObjectNode>("properties", automationProperties)
        }
        runNode.set<ObjectNode>("automationDetails", automationNode)

        runCatching {
            Files.writeString(sarifPath, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root))
        }.onFailure { error ->
            terminal.warn("Failed to write SARIF metadata: ${error.message}")
        }
    }

    private suspend fun prepareScenarioContext(context: ScanContext): ScanContext {
        val startHash = context.runtime.diffStart
        val scenario = context.scenario
        if (scenario == RunScenario.Default || startHash.isNullOrBlank()) {
            return context
        }
        val git = gitClient ?: return context
        val revisionExists = git.revParse(context.paths.repositoryRoot, startHash).isSuccess
        if (revisionExists) {
            return context
        }
        terminal.warn(
            "Cannot run analysis for commit $startHash because it doesn't exist in the repository. Falling back to default analysis.",
        )
        return context.copy(
            scenario = RunScenario.Default,
            runtime =
                context.runtime.copy(
                    commit = null,
                    diffStart = null,
                    diffEnd = null,
                    forceFullHistory = false,
                    forceLocalChangesScript = false,
                    reversePrAnalysis = false,
                    script = "default",
                ),
        )
    }

    private suspend fun runAnalysis(context: ScanContext): Int =
        when (context.scenario) {
            RunScenario.Default -> runSingleAnalysis(context)
            RunScenario.FullHistory -> runFullHistory(context)
            is RunScenario.LocalChanges -> runLocalChanges(context)
            is RunScenario.Scoped -> runScoped(context, context.scenario)
            is RunScenario.ReverseScoped -> runReverseScoped(context, context.scenario)
        }

    private suspend fun runSingleAnalysis(context: ScanContext): Int {
        val pipeline = pipelineFactory.create(context.executionProfile)
        return pipeline.runAnalysis(context)
    }

    private suspend fun runScoped(
        context: ScanContext,
        scenario: RunScenario.Scoped,
    ): Int {
        val git = gitClient ?: return runSingleAnalysis(context)
        val repositoryRoot = context.paths.repositoryRoot
        val endRef =
            context.runtime.diffEnd?.takeIf { it.isNotBlank() }
                ?: git.currentRevision(repositoryRoot).getOrElse {
                    terminal.error("Cannot detect current revision: ${it.message}")
                    return 1
                }
        val scopeFile =
            createScopeFile(context, scenario.targetBranch, endRef).getOrElse {
                terminal.error("Failed to calculate analysis scope: ${it.message}")
                return 1
            } ?: return EMPTY_CHANGESET_EXIT_CODE_PLACEHOLDER

        return try {
            val firstResultsDir = context.paths.resultsDir.resolve("start")
            val firstStageContext =
                scopedStageContext(
                    base = context,
                    script = "scoped:$scopeFile",
                    resultsDir = firstResultsDir,
                    skipFixes = true,
                    clearBaseline = true,
                    additionalPropertyFlags =
                        listOf(
                            "-Dqodana.skip.result=true",
                            "-Dqodana.skip.coverage.computation=true",
                        ),
                )
            val firstExitCode = runStageAtRevision(git, repositoryRoot, scenario.targetBranch, firstStageContext)
            if (shouldStopAfterStage(firstExitCode)) {
                return firstExitCode
            }

            val secondResultsDir = context.paths.resultsDir.resolve("end")
            val baselineSarifPath = firstResultsDir.resolve(SARIF_FILENAME)
            val secondStageContext =
                scopedStageContext(
                    base = context,
                    script = "scoped:$scopeFile",
                    resultsDir = secondResultsDir,
                    skipFixes = false,
                    additionalPropertyFlags =
                        listOf(
                            "-Dqodana.skip.preamble=true",
                            "-Didea.headless.enable.statistics=false",
                            "-Dqodana.scoped.baseline.path=$baselineSarifPath",
                            "-Dqodana.skip.coverage.issues.reporting=true",
                        ),
                )
            val secondExitCode = runStageAtRevision(git, repositoryRoot, endRef, secondStageContext)
            if (shouldStopAfterStage(secondExitCode)) {
                return secondExitCode
            }

            copyStageResults(secondResultsDir, context.paths.resultsDir)
            secondExitCode
        } finally {
            runCatching { Files.deleteIfExists(scopeFile) }
        }
    }

    private suspend fun runReverseScoped(
        context: ScanContext,
        scenario: RunScenario.ReverseScoped,
    ): Int {
        val git = gitClient ?: return runSingleAnalysis(context)
        val repositoryRoot = context.paths.repositoryRoot
        val endRef =
            context.runtime.diffEnd?.takeIf { it.isNotBlank() }
                ?: git.currentRevision(repositoryRoot).getOrElse {
                    terminal.error("Cannot detect current revision: ${it.message}")
                    return 1
                }
        val scopeFile =
            createScopeFile(context, scenario.targetBranch, endRef).getOrElse {
                terminal.error("Failed to calculate analysis scope: ${it.message}")
                return 1
            } ?: return EMPTY_CHANGESET_EXIT_CODE_PLACEHOLDER

        return try {
            val newResultsDir = context.paths.resultsDir.resolve("start")
            val reducedScopePath = newResultsDir.resolve("reduced-scope.json")
            val newStageFlags = mutableListOf("-Dqodana.skip.result.strategy=ANY")
            if (!context.report.baselineIncludeAbsent) {
                newStageFlags += "-Dqodana.reduced.scope.path=$reducedScopePath"
            }
            val newStageContext =
                scopedStageContext(
                    base = context,
                    script = "reverse-scoped:NEW,$scopeFile",
                    resultsDir = newResultsDir,
                    skipFixes = true,
                    additionalPropertyFlags = newStageFlags,
                )
            var exitCode = runStageAtRevision(git, repositoryRoot, endRef, newStageContext)
            if (shouldStopAfterStage(exitCode)) {
                return exitCode
            }

            if (shouldProceedToNextStage(newResultsDir)) {
                copyStageResults(newResultsDir, context.paths.resultsDir)
                return exitCode
            }

            val scopeForNextStage = if (context.report.baselineIncludeAbsent) scopeFile else reducedScopePath
            val baselineSarifPath = newResultsDir.resolve(SARIF_FILENAME)
            val oldResultsDir = context.paths.resultsDir.resolve("end")
            val oldStageFinishStrategy = if (context.runtime.applyFixes || context.runtime.cleanup) "FIXABLE" else "NEVER"
            val oldStageContext =
                scopedStageContext(
                    base = context,
                    script = "reverse-scoped:OLD,$scopeForNextStage",
                    resultsDir = oldResultsDir,
                    skipFixes = true,
                    additionalPropertyFlags =
                        listOf(
                            "-Dqodana.skip.preamble=true",
                            "-Didea.headless.enable.statistics=false",
                            "-Dqodana.skip.result.strategy=$oldStageFinishStrategy",
                            "-Dqodana.scoped.baseline.path=$baselineSarifPath",
                        ),
                )
            copyCoverageFromNewStage(newResultsDir.resolve(COVERAGE_ARTIFACTS_DIR), oldResultsDir)
            exitCode = runStageAtRevision(git, repositoryRoot, scenario.targetBranch, oldStageContext)
            if (shouldStopAfterStage(exitCode)) {
                return exitCode
            }

            var finalResultsDir = oldResultsDir
            if (shouldProceedToNextStage(oldResultsDir)) {
                copyStageResults(finalResultsDir, context.paths.resultsDir)
                return exitCode
            }

            if (context.runtime.applyFixes || context.runtime.cleanup) {
                val fixesResultsDir = context.paths.resultsDir.resolve("fixes")
                val fixesStageContext =
                    scopedStageContext(
                        base = context,
                        script = "reverse-scoped:FIXES,$scopeForNextStage",
                        resultsDir = fixesResultsDir,
                        skipFixes = false,
                        additionalPropertyFlags =
                            listOf(
                                "-Dqodana.skip.preamble=true",
                                "-Didea.headless.enable.statistics=false",
                                "-Dqodana.skip.result.strategy=NEVER",
                                "-Dqodana.scoped.baseline.path=$baselineSarifPath",
                            ),
                    )
                copyCoverageFromNewStage(newResultsDir.resolve(COVERAGE_ARTIFACTS_DIR), fixesResultsDir)
                exitCode = runStageAtRevision(git, repositoryRoot, endRef, fixesStageContext)
                if (shouldStopAfterStage(exitCode)) {
                    return exitCode
                }
                finalResultsDir = fixesResultsDir
            }

            copyStageResults(finalResultsDir, context.paths.resultsDir)
            exitCode
        } finally {
            runCatching { Files.deleteIfExists(scopeFile) }
        }
    }

    private suspend fun runStageAtRevision(
        git: GitClient,
        repositoryRoot: Path,
        revision: String,
        stageContext: ScanContext,
    ): Int {
        val checkoutResult = git.checkout(repositoryRoot, revision)
        if (checkoutResult.isFailure) {
            terminal.error("Cannot checkout revision $revision: ${checkoutResult.exceptionOrNull()?.message}")
            return 1
        }
        val submoduleUpdateResult = git.submoduleUpdate(repositoryRoot, init = true, recursive = true)
        if (submoduleUpdateResult.isFailure) {
            terminal.error(
                "Cannot update submodules for revision $revision: ${submoduleUpdateResult.exceptionOrNull()?.message}",
            )
            return 1
        }
        return runSingleAnalysis(stageContext)
    }

    private fun scopedStageContext(
        base: ScanContext,
        script: String,
        resultsDir: Path,
        skipFixes: Boolean,
        clearBaseline: Boolean = false,
        additionalPropertyFlags: List<String>,
    ): ScanContext {
        Files.createDirectories(resultsDir)
        val runtime =
            base.runtime.copy(
                script = script,
                propertyFlags = base.runtime.propertyFlags + additionalPropertyFlags,
                applyFixes = if (skipFixes) false else base.runtime.applyFixes,
                cleanup = if (skipFixes) false else base.runtime.cleanup,
                fixesStrategy = if (skipFixes) "none" else base.runtime.fixesStrategy,
            )
        return base.copy(
            paths = base.paths.copy(resultsDir = resultsDir),
            runtime = runtime,
            report =
                base.report.copy(
                    showReport = false,
                    saveReport = false,
                    baselinePath = if (clearBaseline) null else base.report.baselinePath,
                ),
        )
    }

    private suspend fun createScopeFile(
        context: ScanContext,
        startRef: String,
        endRef: String,
    ): Result<Path?> {
        val git = gitClient ?: return Result.success(null)
        return git.diff(context.paths.repositoryRoot, startRef, endRef).map { rawDiff ->
            val repoRoot =
                context.paths.repositoryRoot
                    .toAbsolutePath()
                    .normalize()
            val projectDir =
                context.paths.projectDir
                    .toAbsolutePath()
                    .normalize()
            val changedFiles =
                rawDiff
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .mapNotNull { path ->
                        runCatching {
                            val rawPath = Path.of(path)
                            val absolutePath = if (rawPath.isAbsolute) rawPath else repoRoot.resolve(rawPath)
                            absolutePath.normalize()
                        }.getOrNull()
                    }.filter { it.startsWith(projectDir) }
                    .sortedBy { it.toString() }
                    .toList()
            if (changedFiles.isEmpty()) {
                return@map null
            }

            val scopeFile = Files.createTempFile("qodana-diff-scope-", ".json")
            val payload =
                mapOf(
                    "files" to
                        changedFiles.map { path ->
                            mapOf(
                                "path" to path.toString(),
                                "added" to emptyList<Map<String, Int>>(),
                                "deleted" to emptyList<Map<String, Int>>(),
                            )
                        },
                )
            Files.writeString(scopeFile, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
            scopeFile
        }
    }

    private fun shouldProceedToNextStage(resultsDir: Path): Boolean {
        val shortSarifPath = resultsDir.resolve(SHORT_SARIF_FILENAME)
        if (!Files.exists(shortSarifPath)) {
            return false
        }
        return runCatching {
            val root = JSON_MAPPER.readTree(Files.readString(shortSarifPath))
            val skipped =
                root
                    .path("runs")
                    .path(0)
                    .path("invocations")
                    .path(0)
                    .path("properties")
                    .path("qodana.result.skipped")
            if (skipped.isMissingNode || skipped.isNull) {
                return@runCatching false
            }
            when {
                skipped.isTextual -> skipped.asText().equals("false", ignoreCase = true)
                skipped.isBoolean -> !skipped.asBoolean()
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun copyCoverageFromNewStage(
        sourceCoverageDir: Path,
        targetResultsDir: Path,
    ) {
        if (!Files.isDirectory(sourceCoverageDir)) {
            return
        }
        Files.createDirectories(targetResultsDir)
        FileUtils.copyDir(sourceCoverageDir, targetResultsDir.resolve(COVERAGE_ARTIFACTS_DIR))
    }

    private fun copyStageResults(
        fromDir: Path,
        toDir: Path,
    ) {
        if (!Files.isDirectory(fromDir)) {
            terminal.warn("No staged results found at $fromDir")
            return
        }
        Files.createDirectories(toDir)
        FileUtils.copyDir(fromDir, toDir)
    }

    private fun shouldStopAfterStage(exitCode: Int): Boolean = exitCode != ExitCode.SUCCESS.code && exitCode != ExitCode.FAIL_THRESHOLD.code

    private suspend fun runLocalChanges(context: ScanContext): Int {
        val git = gitClient ?: return runSingleAnalysis(context)
        val startHash = context.runtime.diffStart ?: return runSingleAnalysis(context)
        val repositoryRoot = context.paths.repositoryRoot
        var gitReset = false

        val currentRevision = git.currentRevision(repositoryRoot).getOrNull()
        if (!context.runtime.diffEnd.isNullOrBlank() &&
            !currentRevision.isNullOrBlank() &&
            context.runtime.diffEnd != currentRevision
        ) {
            terminal.warn(
                "Cannot run local-changes because --diff-end is ${context.runtime.diffEnd} and HEAD is $currentRevision",
            )
        } else {
            val resetResult = git.reset(repositoryRoot, startHash, hard = false)
            if (resetResult.isSuccess) {
                gitReset = true
            } else {
                terminal.warn(
                    "Could not reset git repository, no --commit option will be applied: ${resetResult.exceptionOrNull()?.message}",
                )
            }
        }

        val contextForRun =
            if (gitReset) {
                context.copy(runtime = context.runtime.copy(script = "local-changes"))
            } else {
                context
            }
        val exitCode = runSingleAnalysis(contextForRun)

        if (gitReset) {
            git.reset(repositoryRoot, "HEAD@{1}", hard = false)
        }
        return exitCode
    }

    private suspend fun runFullHistory(context: ScanContext): Int {
        val git = gitClient ?: return runSingleAnalysis(context)
        val repositoryRoot = context.paths.repositoryRoot

        val remoteUrl = git.remoteUrl(repositoryRoot).getOrDefault("")
        val branch = git.branch(repositoryRoot).getOrDefault("")
        if (remoteUrl.isBlank() && branch.isBlank()) {
            terminal.error(
                "Please check that project is located within the Git repo. If you specified --repository-root option, check that it points to the right directory.",
            )
            return 1
        }

        val cleanResult = git.clean(repositoryRoot, force = true, directories = true)
        if (cleanResult.isFailure) {
            terminal.error("Cannot clean repository: ${cleanResult.exceptionOrNull()?.message}")
            return 1
        }
        val revisions =
            git
                .log(repositoryRoot, "%H")
                .getOrDefault("")
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .reversed()
                .toMutableList()

        val startHash = context.runtime.diffStart
        val filtered =
            if (!startHash.isNullOrBlank()) {
                val idx = revisions.indexOf(startHash)
                if (idx >= 0) revisions.drop(idx) else revisions
            } else {
                revisions
            }

        if (filtered.isEmpty()) {
            return runSingleAnalysis(context)
        }

        var exitCode = 0
        for ((index, revision) in filtered.withIndex()) {
            terminal.warn("[${index + 1}/${filtered.size}] Running analysis for revision $revision")
            val checkout = git.checkout(repositoryRoot, revision)
            if (checkout.isFailure) {
                terminal.error("Cannot checkout revision $revision: ${checkout.exceptionOrNull()?.message}")
                return 1
            }
            val updateResult = git.submoduleUpdate(repositoryRoot, init = true, recursive = true)
            if (updateResult.isFailure) {
                terminal.error("Cannot update submodules for revision $revision: ${updateResult.exceptionOrNull()?.message}")
                return 1
            }

            val contextForIteration =
                context.copy(
                    ci =
                        context.ci.copy(
                            remoteUrl = if (remoteUrl.isBlank()) context.ci.remoteUrl else remoteUrl,
                            branch = if (branch.isBlank()) context.ci.branch else branch,
                            revision = revision,
                        ),
                )
            exitCode = runSingleAnalysis(contextForIteration)
        }

        if (branch.isNotBlank()) {
            val checkoutResult = git.checkout(repositoryRoot, branch)
            if (checkoutResult.isFailure) {
                terminal.error("Cannot checkout branch $branch: ${checkoutResult.exceptionOrNull()?.message}")
                return 1
            }
            val updateResult = git.submoduleUpdate(repositoryRoot, init = true, recursive = true)
            if (updateResult.isFailure) {
                terminal.error("Cannot update submodules for branch $branch: ${updateResult.exceptionOrNull()?.message}")
                return 1
            }
        }
        return exitCode
    }

    private suspend fun validateGit(context: ScanContext) {
        if (gitClient == null) return
        val needsGit =
            context.runtime.diffStart != null ||
                context.runtime.commit != null ||
                context.runtime.diffEnd != null ||
                context.scenario != RunScenario.Default

        if (needsGit) {
            try {
                gitClient.currentRevision(context.paths.repositoryRoot)
            } catch (e: Exception) {
                terminal.warn("Git validation failed: ${e.message}")
            }
        }
    }

    companion object {
        // Matches Go CLI placeholder semantics in utils/cmd.go.
        private const val TIMEOUT_EXIT_CODE_PLACEHOLDER = 1000
        private const val EMPTY_CHANGESET_EXIT_CODE_PLACEHOLDER = 2000
        private const val SARIF_FILENAME = "qodana.sarif.json"
        private const val SHORT_SARIF_FILENAME = "qodana-short.sarif.json"
        private const val COVERAGE_ARTIFACTS_DIR = "coverage"
        private val JSON_MAPPER = ObjectMapper()
    }
}

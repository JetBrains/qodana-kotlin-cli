package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.engine.cloud.LicenseValidator
import org.jetbrains.qodana.engine.config.EffectiveConfig
import org.jetbrains.qodana.engine.report.ReportProcessor
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.startup.PrepareHost
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.engine.model.RunScenario
import org.jetbrains.qodana.engine.model.ScanContext
import org.jetbrains.qodana.engine.port.GitClient
import org.jetbrains.qodana.core.port.Terminal
import org.slf4j.LoggerFactory

class ScanUseCase(
    private val prepareHost: PrepareHost,
    private val nativeScan: NativeScan,
    private val containerScan: ContainerScan,
    private val reportProcessor: ReportProcessor,
    private val reportPublisher: ReportPublishUseCase?,
    private val licenseValidator: LicenseValidator?,
    private val gitClient: GitClient?,
    private val terminal: Terminal,
) {
    private val log = LoggerFactory.getLogger(ScanUseCase::class.java)

    suspend fun run(context: ScanContext): Int {
        val yaml = EffectiveConfig.load(context.paths.projectDir)
        val effectiveContext = EffectiveConfig.merge(yaml, context)

        val prepared = prepareHost.prepare(effectiveContext)
        val effectiveContextWithIde = if (prepared.ideDir != null && effectiveContext.runtime.ideDir == null) {
            effectiveContext.copy(runtime = effectiveContext.runtime.copy(ideDir = prepared.ideDir))
        } else {
            effectiveContext
        }

        if (effectiveContextWithIde.auth.hasToken && licenseValidator != null) {
            val licenseResult = licenseValidator.validate(effectiveContextWithIde.auth.token!!)
            licenseResult.onSuccess { licenseData ->
                System.setProperty(QodanaEnv.PROJECT_ID_HASH, licenseData.projectIdHash)
                System.setProperty(QodanaEnv.ORGANISATION_ID_HASH, licenseData.organisationIdHash)
            }
            licenseResult.onFailure { e ->
                terminal.warn("License validation failed: ${e.message}")
            }
        }

        val plugins = EffectiveConfig.resolvePlugins(yaml)
        if (plugins.isNotEmpty() && effectiveContextWithIde.nativeMode) {
            log.info("Plugins to install: {}", plugins.map { it.id })
        }

        val dotnetConfig = EffectiveConfig.resolveDotNet(yaml)
        if (dotnetConfig != null) {
            log.info("DotNet configuration: solution={}, project={}", dotnetConfig.solution, dotnetConfig.project)
        }

        val bootstrap = EffectiveConfig.resolveBootstrap(yaml)
        if (bootstrap != null && effectiveContextWithIde.nativeMode) {
            terminal.println("Running bootstrap: $bootstrap")
        }

        validateGit(effectiveContextWithIde)

        val exitCode = runAnalysis(effectiveContextWithIde)

        val processedReport = reportProcessor.process(
            resultsDir = effectiveContextWithIde.paths.resultsDir,
            reportDir = effectiveContextWithIde.paths.reportDir,
            reportOptions = effectiveContextWithIde.report,
            failThreshold = effectiveContextWithIde.runtime.failThreshold,
            analysisExitCode = exitCode,
        )

        terminal.println("Analysis complete: ${processedReport.totalProblems} problems found" +
            (processedReport.baselineResult?.let { " (${it.newCount} new)" } ?: ""))

        if (effectiveContextWithIde.auth.hasToken && !effectiveContextWithIde.auth.isLicenseOnly && reportPublisher != null) {
            val publishResult = reportPublisher.publish(
                analysisId = effectiveContextWithIde.runtime.analysisId ?: "",
                reportPath = effectiveContextWithIde.paths.resultsDir,
                auth = effectiveContextWithIde.auth,
            )
            publishResult.onSuccess { result ->
                if (result.success) {
                    terminal.println("Report published: ${result.url}")
                }
            }
        }

        return processedReport.exitCode.code
    }

    private suspend fun runAnalysis(context: ScanContext): Int {
        return when (context.scenario) {
            RunScenario.Default -> runSingleAnalysis(context)
            RunScenario.FullHistory -> runFullHistory(context)
            is RunScenario.LocalChanges -> runSingleAnalysis(context)
            is RunScenario.Scoped -> runSingleAnalysis(context)
            is RunScenario.ReverseScoped -> runSingleAnalysis(context)
        }
    }

    private suspend fun runSingleAnalysis(context: ScanContext): Int {
        return if (context.nativeMode) {
            nativeScan.run(context)
        } else {
            containerScan.run(context)
        }
    }

    private suspend fun runFullHistory(context: ScanContext): Int {
        log.info("Running full history analysis")
        return runSingleAnalysis(context)
    }

    private suspend fun validateGit(context: ScanContext) {
        if (gitClient == null) return
        val needsGit = context.runtime.diffStart != null ||
            context.runtime.diffEnd != null ||
            context.scenario != RunScenario.Default

        if (needsGit) {
            try {
                gitClient.currentRevision(context.paths.projectDir)
            } catch (e: Exception) {
                terminal.warn("Git validation failed: ${e.message}")
            }
        }
    }
}

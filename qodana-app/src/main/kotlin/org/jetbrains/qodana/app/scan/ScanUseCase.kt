package org.jetbrains.qodana.app.scan

import org.jetbrains.qodana.app.cloud.CloudClient
import org.jetbrains.qodana.app.cloud.LicenseValidator
import org.jetbrains.qodana.app.config.EffectiveConfig
import org.jetbrains.qodana.app.report.ReportProcessor
import org.jetbrains.qodana.app.report.ReportPublishUseCase
import org.jetbrains.qodana.app.startup.PrepareHost
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.model.RunScenario
import org.jetbrains.qodana.core.model.ScanContext
import org.jetbrains.qodana.core.port.GitClient
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

        if (effectiveContext.auth.hasToken && licenseValidator != null) {
            val licenseResult = licenseValidator.validate(effectiveContext.auth.token!!)
            if (licenseResult.isFailure) {
                terminal.warn("License validation failed: ${licenseResult.exceptionOrNull()?.message}")
            }
        }

        val bootstrap = EffectiveConfig.resolveBootstrap(yaml)
        if (bootstrap != null && effectiveContext.nativeMode) {
            terminal.println("Running bootstrap: $bootstrap")
        }

        validateGit(effectiveContext)

        val exitCode = runAnalysis(effectiveContext)

        val processedReport = reportProcessor.process(
            resultsDir = effectiveContext.paths.resultsDir,
            reportDir = effectiveContext.paths.reportDir,
            reportOptions = effectiveContext.report,
            failThreshold = effectiveContext.runtime.failThreshold,
        )

        terminal.println("Analysis complete: ${processedReport.totalProblems} problems found" +
            (processedReport.baselineResult?.let { " (${it.newCount} new)" } ?: ""))

        if (effectiveContext.auth.hasToken && !effectiveContext.auth.isLicenseOnly && reportPublisher != null) {
            val publishResult = reportPublisher.publish(
                analysisId = effectiveContext.runtime.analysisId ?: "",
                reportPath = effectiveContext.paths.resultsDir,
                auth = effectiveContext.auth,
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

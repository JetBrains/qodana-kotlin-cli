package org.jetbrains.qodana.cli

import org.jetbrains.qodana.core.fs.NioFileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.engine.git.SystemGitClient
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.reportconverter.ReportConverterAdapter

/**
 * Bundles all scan-side dependencies so [buildScanUseCase] stays under detekt's
 * parameter-count limit. Any future scan dependency must be added here and
 * threaded through both the production [main] call site and `NativeSmokeTest`.
 */
internal data class ScanDeps(
    val httpTransport: OkHttpTransport,
    val containerEngine: DockerJavaEngine,
    val sarifService: QodanaSarifService,
    val reportConverter: ReportConverterAdapter,
    val fileSystem: NioFileSystem,
    val reportPublishUseCase: ReportPublishUseCase,
    val processRunner: SystemProcessRunner,
    val gitClient: SystemGitClient,
    val terminal: Terminal,
)

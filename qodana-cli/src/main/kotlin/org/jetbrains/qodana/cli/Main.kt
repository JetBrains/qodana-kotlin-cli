package org.jetbrains.qodana.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.app.cloud.CloudClient
import org.jetbrains.qodana.app.cloud.LicenseValidator
import org.jetbrains.qodana.app.contributors.ContributorAnalyzer
import org.jetbrains.qodana.app.report.ReportProcessor
import org.jetbrains.qodana.app.report.ReportPublishUseCase
import org.jetbrains.qodana.app.scan.ContainerScan
import org.jetbrains.qodana.app.scan.NativeScan
import org.jetbrains.qodana.app.scan.ScanUseCase
import org.jetbrains.qodana.app.startup.PrepareHost
import org.jetbrains.qodana.cli.command.*
import org.jetbrains.qodana.infra.dockerjava.DockerJavaEngine
import org.jetbrains.qodana.infra.fs.NioFileSystem
import org.jetbrains.qodana.infra.gitcli.SystemGitClient
import org.jetbrains.qodana.infra.http.OkHttpTransport
import org.jetbrains.qodana.infra.process.SystemProcessRunner
import org.jetbrains.qodana.infra.publisher.PublisherAdapter
import org.jetbrains.qodana.infra.reportconverter.ReportConverterAdapter
import org.jetbrains.qodana.infra.sarif.QodanaSarifService
import org.jetbrains.qodana.infra.terminal.MordantTerminal

fun main(args: Array<String>) {
    val processRunner = SystemProcessRunner()
    val gitClient = SystemGitClient(processRunner)
    val httpTransport = OkHttpTransport()
    val containerEngine = DockerJavaEngine()
    val sarifService = QodanaSarifService()
    val reportConverter = ReportConverterAdapter()
    val fileSystem = NioFileSystem()
    val terminal = MordantTerminal()
    val publisher = PublisherAdapter()
    val reportPublishUseCase = ReportPublishUseCase(publisher)
    val contributorAnalyzer = ContributorAnalyzer(gitClient)

    fun createScanUseCase(): ScanUseCase {
        val token = System.getenv("QODANA_TOKEN")
        val cloudClient = if (!token.isNullOrBlank()) {
            CloudClient(httpTransport, token = token)
        } else null

        return ScanUseCase(
            prepareHost = PrepareHost(fileSystem, terminal),
            nativeScan = NativeScan(processRunner, fileSystem),
            containerScan = ContainerScan(containerEngine, terminal),
            reportProcessor = ReportProcessor(sarifService, reportConverter),
            reportPublisher = reportPublishUseCase,
            licenseValidator = cloudClient?.let { LicenseValidator(httpTransport, it) },
            gitClient = gitClient,
            terminal = terminal,
        )
    }

    QodanaCommand().subcommands(
        ScanCommand(::createScanUseCase),
        InitCommand(terminal),
        PullCommand(containerEngine, terminal),
        ShowCommand(terminal),
        SendCommand(reportPublishUseCase, terminal),
        ContributorsCommand(contributorAnalyzer, terminal),
    ).main(args)
}

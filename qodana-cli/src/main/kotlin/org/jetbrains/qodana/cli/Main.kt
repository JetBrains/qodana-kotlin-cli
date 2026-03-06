package org.jetbrains.qodana.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.engine.cloud.CloudClient
import org.jetbrains.qodana.engine.cloud.LicenseValidator
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import org.jetbrains.qodana.engine.report.ReportProcessor
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.report.BitBucketExporter
import org.jetbrains.qodana.engine.report.CodeClimateExporter
import org.jetbrains.qodana.engine.scan.SystemUtils
import org.jetbrains.qodana.engine.scan.ContainerScan
import org.jetbrains.qodana.engine.scan.NativeScan
import org.jetbrains.qodana.engine.scan.ScanUseCase
import org.jetbrains.qodana.engine.startup.IdeInstaller
import org.jetbrains.qodana.engine.startup.PrepareHost
import org.jetbrains.qodana.cli.command.*
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.core.fs.NioFileSystem
import org.jetbrains.qodana.engine.git.SystemGitClient
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.engine.publisher.PublisherAdapter
import org.jetbrains.qodana.engine.reportconverter.ReportConverterAdapter
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.core.terminal.MordantTerminal
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.engine.env.CiDetector

private val ROOT_COMMANDS = setOf(
    "scan",
    "init",
    "pull",
    "show",
    "send",
    "contributors",
    "view",
    "cloc",
    "completion",
)

private val HELP_OR_VERSION_ARGS = setOf("--help", "-h", "help", "--version", "-v")

private fun normalizeRootArgs(args: Array<String>): Array<String> {
    if (args.isEmpty()) return arrayOf("scan")

    val working = args.toMutableList()
    val rootFlags = mutableListOf<String>()
    var i = 0
    while (i < working.size) {
        val arg = working[i]
        when {
            arg == "--disable-update-checks" -> {
                rootFlags += arg
                working.removeAt(i)
            }
            arg == "--log-level" && i + 1 < working.size -> {
                rootFlags += arg
                rootFlags += working[i + 1]
                working.removeAt(i)
                working.removeAt(i)
            }
            arg.startsWith("--log-level=") -> {
                rootFlags += arg
                working.removeAt(i)
            }
            else -> i++
        }
    }

    val normalized = when {
        working.isEmpty() -> listOf("scan")
        working.size == 1 && working[0] in HELP_OR_VERSION_ARGS -> working
        working.first() == "completion" -> working
        working.any { it in ROOT_COMMANDS } -> working
        else -> listOf("scan") + working
    }
    return (rootFlags + normalized).toTypedArray()
}

private fun shouldCheckUpdates(args: Array<String>, isContainer: Boolean, isCi: Boolean): Boolean {
    if (isContainer || isCi) return false
    return args.none { it == "--disable-update-checks" }
}

private fun isRunningAsRoot(): Boolean {
    val os = System.getProperty("os.name", "").lowercase()
    if (os.contains("win")) return false
    return runCatching {
        val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor() == 0 && output == "0"
    }.getOrDefault(false)
}

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

    val isContainer = !System.getenv(QodanaEnv.DOCKER).isNullOrBlank()
    val isCi = CiDetector.detect() != null
    terminal.isCi = isCi

    if (!isContainer && isRunningAsRoot()) {
        terminal.warn("Running the tool as root is dangerous: please run it as a regular user")
    }

    if (shouldCheckUpdates(args, isContainer, isCi)) {
        val latest = SystemUtils.checkForUpdates(QodanaCommand.VERSION)
        if (latest != null) {
            terminal.warn("New version of qodana CLI is available: $latest. See https://jb.gg/qodana-cli/update")
        }
    }

    fun createScanUseCase(): ScanUseCase {
        val token = System.getenv("QODANA_TOKEN")
        val cloudClient = if (!token.isNullOrBlank()) {
            CloudClient(httpTransport, token = token)
        } else null
        val ideInstaller = IdeInstaller(httpTransport, fileSystem, terminal)
        val codeClimateExporter = CodeClimateExporter(sarifService)
        val bitBucketExporter = BitBucketExporter(sarifService, httpTransport)

        return ScanUseCase(
            prepareHost = PrepareHost(fileSystem, terminal, ideInstaller),
            nativeScan = NativeScan(processRunner, fileSystem),
            containerScan = ContainerScan(containerEngine, terminal),
            reportProcessor = ReportProcessor(sarifService, reportConverter),
            reportPublisher = reportPublishUseCase,
            licenseValidator = cloudClient?.let { LicenseValidator(httpTransport, it) },
            codeClimateExporter = codeClimateExporter,
            bitBucketExporter = bitBucketExporter,
            gitClient = gitClient,
            terminal = terminal,
        )
    }

    val normalizedArgs = normalizeRootArgs(args)

    QodanaCommand().subcommands(
        ScanCommand { context -> createScanUseCase().run(context) },
        InitCommand(terminal),
        PullCommand(containerEngine, terminal),
        ShowCommand(terminal),
        SendCommand(reportPublishUseCase, terminal),
        ContributorsCommand(contributorAnalyzer, terminal),
        ViewCommand(sarifService, terminal),
        ClocCommand(terminal),
        CompletionCommand(
            name = "completion",
            help = "Generate the autocompletion script for the specified shell",
        ),
    ).main(normalizedArgs)
}

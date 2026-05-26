package org.jetbrains.qodana.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.cli.command.*
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.fs.NioFileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.core.terminal.MordantTerminal
import org.jetbrains.qodana.engine.cloud.CloudClient
import org.jetbrains.qodana.engine.cloud.LicenseValidator
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.engine.env.CiDetector
import org.jetbrains.qodana.engine.env.RuntimeEnvironment
import org.jetbrains.qodana.engine.env.RuntimeEnvironmentDetector
import org.jetbrains.qodana.engine.git.SystemGitClient
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.publisher.PublisherAdapter
import org.jetbrains.qodana.engine.report.BitBucketExporter
import org.jetbrains.qodana.engine.report.CodeClimateExporter
import org.jetbrains.qodana.engine.report.ReportProcessor
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.reportconverter.ReportConverterAdapter
import org.jetbrains.qodana.engine.scan.ContainerScan
import org.jetbrains.qodana.engine.scan.NativeScan
import org.jetbrains.qodana.engine.scan.ScanUseCase
import org.jetbrains.qodana.engine.scan.SystemUtils
import org.jetbrains.qodana.engine.startup.IdeInstaller
import org.jetbrains.qodana.engine.startup.PrepareHost

/**
 * Builds the [ScanUseCase] with the wiring the production `main()` uses.
 *
 * Extracted (QD-14728) so `NativeSmokeTest` can construct the same use-case
 * graph in its `scanRunner` slot without drifting from Main.kt's wiring. Any
 * future dependency added to scan must be threaded through both the call site
 * here and the one in `main()`.
 */
internal fun buildScanUseCase(
    httpTransport: OkHttpTransport,
    containerEngine: DockerJavaEngine,
    sarifService: QodanaSarifService,
    reportConverter: ReportConverterAdapter,
    fileSystem: NioFileSystem,
    reportPublishUseCase: ReportPublishUseCase,
    processRunner: SystemProcessRunner,
    gitClient: SystemGitClient,
    terminal: Terminal,
): ScanUseCase {
    val token =
        System.getenv(QodanaEnv.TOKEN)?.takeIf { it.isNotBlank() }
            ?: System.getenv(QodanaEnv.LICENSE_ONLY_TOKEN)?.takeIf { it.isNotBlank() }
    val cloudClient =
        if (!token.isNullOrBlank()) {
            CloudClient(httpTransport, token = token)
        } else {
            null
        }
    val ideInstaller = IdeInstaller(httpTransport, fileSystem, terminal)
    val codeClimateExporter = CodeClimateExporter(sarifService)
    val bitBucketExporter = BitBucketExporter(sarifService, httpTransport)

    return ScanUseCase(
        prepareHost = PrepareHost(fileSystem, terminal, ideInstaller),
        nativeScan = NativeScan(processRunner, fileSystem, terminal),
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

private val ROOT_COMMANDS =
    setOf(
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

    val normalized =
        when {
            working.isEmpty() -> listOf("scan")
            working.size == 1 && working[0] in HELP_OR_VERSION_ARGS -> working
            working.first() == "completion" -> working
            working.any { it in ROOT_COMMANDS } -> working
            else -> listOf("scan") + working
        }
    return (rootFlags + normalized).toTypedArray()
}

private fun shouldCheckUpdates(
    args: Array<String>,
    isContainer: Boolean,
    isCi: Boolean,
): Boolean {
    if (isContainer || isCi) return false
    return args.none { it == "--disable-update-checks" }
}

private fun isRunningAsRoot(): Boolean {
    val os = System.getProperty("os.name", "").lowercase()
    if (os.contains("win")) return false
    return runCatching {
        val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        process.waitFor() == 0 && output == "0"
    }.getOrDefault(false)
}

fun main(args: Array<String>) {
    // Eager: lightweight; needed for root checks below.
    val processRunner = SystemProcessRunner()
    val gitClient = SystemGitClient(processRunner)
    val terminal = MordantTerminal()

    // Lazy: heavy constructors that pull large class graphs (Jackson, OkHttp, docker-java,
    // intellij-report-converter, qodana-publisher). Keeping these lazy means `qodana --help`
    // and `qodana --version` don't drag the full dependency surface into the native image's
    // startup path.
    val httpTransport: OkHttpTransport by lazy { OkHttpTransport() }
    val containerEngine: DockerJavaEngine by lazy { DockerJavaEngine() }
    val sarifService: QodanaSarifService by lazy { QodanaSarifService() }
    val reportConverter: ReportConverterAdapter by lazy { ReportConverterAdapter() }
    val fileSystem: NioFileSystem by lazy { NioFileSystem() }
    val publisher: PublisherAdapter by lazy { PublisherAdapter() }
    val reportPublishUseCase: ReportPublishUseCase by lazy { ReportPublishUseCase(publisher) }
    val contributorAnalyzer: ContributorAnalyzer by lazy { ContributorAnalyzer(gitClient) }

    val runtimeEnvironment = RuntimeEnvironmentDetector.detect()
    val isCi = CiDetector.detect() != null
    terminal.isCi = isCi

    if (runtimeEnvironment == RuntimeEnvironment.HOST && isRunningAsRoot()) {
        terminal.warn("Running the tool as root is dangerous: please run it as a regular user")
    }

    if (shouldCheckUpdates(args, runtimeEnvironment == RuntimeEnvironment.IN_DOCKER, isCi)) {
        val latest = SystemUtils.checkForUpdates(QodanaCommand.VERSION)
        if (latest != null) {
            terminal.warn("New version of qodana CLI is available: $latest. See https://jb.gg/qodana-cli/update")
        }
    }

    val normalizedArgs = normalizeRootArgs(args)

    QodanaCommand()
        .subcommands(
            ScanCommand(
                scanRunner = { context ->
                    buildScanUseCase(
                        httpTransport = httpTransport,
                        containerEngine = containerEngine,
                        sarifService = sarifService,
                        reportConverter = reportConverter,
                        fileSystem = fileSystem,
                        reportPublishUseCase = reportPublishUseCase,
                        processRunner = processRunner,
                        gitClient = gitClient,
                        terminal = terminal,
                    ).run(context)
                },
                terminal = terminal,
            ),
            InitCommand(terminal),
            PullCommand({ containerEngine }, terminal),
            ShowCommand(terminal),
            SendCommand(
                reportPublisher = { reportPublishUseCase },
                terminal = terminal,
                httpTransport = { httpTransport },
            ),
            ContributorsCommand({ contributorAnalyzer }, terminal),
            ViewCommand({ sarifService }, terminal),
            ClocCommand(terminal),
            CompletionCommand(
                name = "completion",
                help = "Generate the autocompletion script for the specified shell",
            ),
        ).main(normalizedArgs)
}

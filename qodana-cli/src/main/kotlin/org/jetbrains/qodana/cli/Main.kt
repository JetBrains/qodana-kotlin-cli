package org.jetbrains.qodana.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.cli.command.*
import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.fs.NioFileSystem
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
 * future dependency added to scan must be added to [ScanDeps].
 */
internal fun buildScanUseCase(deps: ScanDeps): ScanUseCase {
    val token =
        System.getenv(QodanaEnv.TOKEN)?.takeIf { it.isNotBlank() }
            ?: System.getenv(QodanaEnv.LICENSE_ONLY_TOKEN)?.takeIf { it.isNotBlank() }
    val cloudClient =
        if (!token.isNullOrBlank()) {
            CloudClient(deps.httpTransport, token = token)
        } else {
            null
        }
    val ideInstaller = IdeInstaller(deps.httpTransport, deps.fileSystem, deps.terminal)
    val codeClimateExporter = CodeClimateExporter(deps.sarifService)
    val bitBucketExporter = BitBucketExporter(deps.sarifService, deps.httpTransport)

    return ScanUseCase(
        prepareHost = PrepareHost(deps.fileSystem, deps.terminal, ideInstaller),
        nativeScan = NativeScan(deps.processRunner, deps.fileSystem, deps.terminal),
        containerScan = ContainerScan(deps.containerEngine, deps.terminal),
        reportProcessor = ReportProcessor(deps.sarifService, deps.reportConverter),
        reportPublisher = deps.reportPublishUseCase,
        licenseValidator = cloudClient?.let { LicenseValidator(deps.httpTransport, it) },
        codeClimateExporter = codeClimateExporter,
        bitBucketExporter = bitBucketExporter,
        gitClient = deps.gitClient,
        terminal = deps.terminal,
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

// Clikt doesn't treat the bare word `help` as a special subcommand — it would
// fall through to the "no such command 'help'" error if a user typed
// `qodana help`. Only `--help`/`-h`/`--version`/`-v` are the actual short
// circuits that normalizeRootArgs needs to recognise.
private val HELP_OR_VERSION_ARGS = setOf("--help", "-h", "--version", "-v")

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

/**
 * Wires lazy heavy dependencies and all subcommands into a ready-to-run
 * [QodanaCommand]. Extracted from [main] to keep that function under detekt's
 * line-length limit.
 *
 * All heavy constructors (OkHttp, docker-java, Jackson, publisher) are kept
 * lazy so `qodana --help` / `--version` don't drag the full dependency surface
 * into the native-image startup path.
 */
private fun buildQodanaCommand(
    processRunner: SystemProcessRunner,
    gitClient: SystemGitClient,
    terminal: MordantTerminal,
): QodanaCommand {
    val httpTransport: OkHttpTransport by lazy { OkHttpTransport() }
    val containerEngine: DockerJavaEngine by lazy { DockerJavaEngine() }
    val sarifService: QodanaSarifService by lazy { QodanaSarifService() }
    val reportConverter: ReportConverterAdapter by lazy { ReportConverterAdapter() }
    val fileSystem: NioFileSystem by lazy { NioFileSystem() }
    val publisher: PublisherAdapter by lazy { PublisherAdapter() }
    val reportPublishUseCase: ReportPublishUseCase by lazy { ReportPublishUseCase(publisher) }
    val contributorAnalyzer: ContributorAnalyzer by lazy { ContributorAnalyzer(gitClient) }

    val scanDeps by lazy {
        ScanDeps(
            httpTransport,
            containerEngine,
            sarifService,
            reportConverter,
            fileSystem,
            reportPublishUseCase,
            processRunner,
            gitClient,
            terminal,
        )
    }

    return QodanaCommand().subcommands(
        ScanCommand(
            scanRunner = { context -> buildScanUseCase(scanDeps).run(context) },
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
    )
}

fun main(args: Array<String>) {
    val processRunner = SystemProcessRunner()
    val gitClient = SystemGitClient(processRunner)
    val terminal = MordantTerminal()
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

    buildQodanaCommand(processRunner, gitClient, terminal)
        .main(normalizeRootArgs(args))
}

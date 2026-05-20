package org.jetbrains.qodana.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.cli.command.ClocCommand
import org.jetbrains.qodana.cli.command.ContributorsCommand
import org.jetbrains.qodana.cli.command.InitCommand
import org.jetbrains.qodana.cli.command.PullCommand
import org.jetbrains.qodana.cli.command.QodanaCommand
import org.jetbrains.qodana.cli.command.ScanCommand
import org.jetbrains.qodana.cli.command.SendCommand
import org.jetbrains.qodana.cli.command.ShowCommand
import org.jetbrains.qodana.cli.command.ViewCommand
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.engine.git.SystemGitClient
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.publisher.PublisherAdapter
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mirrors Main.kt's subcommand tree construction so we exercise the same
 * reflective surface the native binary will hit on the Phase-A code paths:
 *
 *   - `--version`, `--help`
 *   - `<subcommand> --help` for each registered subcommand
 *   - `init` against a fixture project directory
 *
 * Under `-Pagent`, this captures reachability metadata for Clikt's
 * option/argument reflection plus InitCommand's file-IO path. Heavy deps
 * (DockerJavaEngine, OkHttpTransport, etc.) stay behind `by lazy` and are
 * not constructed by any test here — matching the native binary's behaviour
 * for these subcommands. `scan` execution is out of scope (QD-14728).
 */
class NativeSmokeTest {
    private val output = mutableListOf<String>()

    private val terminal =
        object : Terminal {
            override fun print(message: String) {
                output.add(message)
            }

            override fun println(message: String) {
                output.add(message)
            }

            override fun error(message: String) {
                output.add("ERROR: $message")
            }

            override fun info(message: String) {
                output.add("INFO: $message")
            }

            override fun warn(message: String) {
                output.add("WARN: $message")
            }

            override fun debug(message: String) {
                output.add("DEBUG: $message")
            }

            override fun <T> spinner(
                message: String,
                action: () -> T,
            ): T = action()

            override fun prompt(
                message: String,
                default: String?,
            ): String = default ?: ""

            override fun select(
                message: String,
                choices: List<String>,
            ): String = choices.first()

            override val isInteractive = false
            override var isCi = false

            override fun setRedactedTokens(tokens: Set<String>) = Unit
        }

    private fun buildRootCommand(): QodanaCommand {
        // Lightweight (constructed eagerly by Main.kt as well).
        val processRunner = SystemProcessRunner()
        val gitClient = SystemGitClient(processRunner)

        // Heavy — kept lazy to mirror Main.kt. None of these will be evaluated for
        // the `--help`/`--version`/`init --help` flows below.
        val httpTransport: OkHttpTransport by lazy { OkHttpTransport() }
        val containerEngine: DockerJavaEngine by lazy { DockerJavaEngine() }
        val sarifService: QodanaSarifService by lazy { QodanaSarifService() }
        val publisher: PublisherAdapter by lazy { PublisherAdapter() }
        val reportPublishUseCase: ReportPublishUseCase by lazy { ReportPublishUseCase(publisher) }
        val contributorAnalyzer: ContributorAnalyzer by lazy { ContributorAnalyzer(gitClient) }

        return QodanaCommand().subcommands(
            ScanCommand(
                scanRunner = { _ -> error("scan execution is out of Phase-A smoke-test scope") },
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

    @Test
    fun `version flag prints embedded version`() {
        val ex =
            assertFailsWith<PrintMessage> {
                buildRootCommand().parse(listOf("--version"))
            }
        // BuildInfo.VERSION is the source of truth — see qodana-cli/build.gradle.kts.
        // Asserting against the constant catches regressions where build-info
        // generation silently emits an empty or wrong version.
        assertContains(ex.message ?: "", BuildInfo.VERSION)
    }

    @Test
    fun `root help lists every subcommand`() {
        val ex =
            assertFailsWith<PrintHelpMessage> {
                buildRootCommand().parse(listOf("--help"))
            }
        val help = ex.context?.command?.getFormattedHelp() ?: ""
        for (sub in SUBCOMMAND_NAMES) {
            assertContains(help, sub, message = "root --help should list `$sub`")
        }
    }

    @Test
    fun `per-subcommand help exits cleanly for every Phase-A subcommand`() {
        for (sub in SUBCOMMAND_NAMES) {
            // Each subcommand's --help prints via PrintHelpMessage. The fact that
            // parse() throws (rather than crashing with MissingReflectionRegistrationError)
            // is the proof Phase A cares about — Clikt's option-class reflection is
            // captured by the agent run for the native image.
            assertFailsWith<PrintHelpMessage>(
                message = "`$sub --help` must throw PrintHelpMessage",
            ) {
                buildRootCommand().parse(listOf(sub, "--help"))
            }
        }
    }

    @Test
    fun `init writes qodana yaml for a detected jvm project`(
        @TempDir dir: Path,
    ) {
        // ProjectDetector picks the JVM linter when it sees a Gradle/Maven file.
        val projectDir = Files.createDirectories(dir.resolve("project"))
        projectDir.resolve("build.gradle.kts").writeText("// fixture for native smoke test")

        buildRootCommand().parse(listOf("init", "-i", projectDir.toString()))

        val yaml = projectDir.resolve("qodana.yaml")
        assertTrue(Files.exists(yaml), "init should create qodana.yaml")
        val content = Files.readString(yaml)
        assertContains(content, "linter:", message = "qodana.yaml should declare a linter")
    }

    @Test
    fun `scan with unknown flag raises a Clikt UsageError`() {
        // Clikt's error formatter reflects on the option class to print "no such option" — a
        // common surface for MissingReflectionRegistrationError in the native binary. Recording
        // this path under the agent ensures the relevant metadata is captured.
        val ex =
            assertFailsWith<UsageError> {
                buildRootCommand().parse(listOf("scan", "--definitely-not-a-flag"))
            }
        assertTrue(
            ex is NoSuchOption || (ex.message?.contains("--definitely-not-a-flag") == true),
            "expected NoSuchOption / unknown-flag message, got ${ex::class.simpleName}: ${ex.message}",
        )
    }

    @Test
    fun `init -f overwrites an existing qodana yaml`(
        @TempDir dir: Path,
    ) {
        val projectDir = Files.createDirectories(dir.resolve("project"))
        projectDir.resolve("build.gradle.kts").writeText("// fixture")
        val yaml = projectDir.resolve("qodana.yaml")
        yaml.writeText("version: \"1.0\"\nlinter: stale\n")

        buildRootCommand().parse(listOf("init", "-i", projectDir.toString(), "-f"))

        // Either updates the existing file in place or rewrites it — both are valid;
        // the assertion is that the linter field no longer says "stale".
        val content = Files.readString(yaml)
        assertTrue(!content.contains("linter: stale"), "stale linter should have been replaced: $content")
    }

    private companion object {
        val SUBCOMMAND_NAMES =
            listOf(
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
    }
}

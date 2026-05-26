package org.jetbrains.qodana.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.cli.command.ClocCommand
import org.jetbrains.qodana.cli.command.ContributorsCommand
import org.jetbrains.qodana.cli.command.InitCommand
import org.jetbrains.qodana.cli.command.PullCommand
import org.jetbrains.qodana.cli.command.QodanaCommand
import org.jetbrains.qodana.cli.command.ScanCommand
import org.jetbrains.qodana.cli.command.SendCommand
import org.jetbrains.qodana.cli.command.ShowCommand
import org.jetbrains.qodana.cli.command.ViewCommand
import org.jetbrains.qodana.core.fs.NioFileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.engine.git.SystemGitClient
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.publisher.PublisherAdapter
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.reportconverter.ReportConverterAdapter
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

        // Heavy — kept lazy to mirror Main.kt. The full dependency set is needed
        // here (not just containerEngine) because QD-14728 wires the real
        // scanRunner via buildScanUseCase(), which takes nine deps. Lazy values
        // still gate Phase-A's --help/--version/init flows from constructing
        // anything heavy.
        val httpTransport: OkHttpTransport by lazy { OkHttpTransport() }
        val containerEngine: DockerJavaEngine by lazy { DockerJavaEngine() }
        val sarifService: QodanaSarifService by lazy { QodanaSarifService() }
        val reportConverter: ReportConverterAdapter by lazy { ReportConverterAdapter() }
        val fileSystem: NioFileSystem by lazy { NioFileSystem() }
        val publisher: PublisherAdapter by lazy { PublisherAdapter() }
        val reportPublishUseCase: ReportPublishUseCase by lazy { ReportPublishUseCase(publisher) }
        val contributorAnalyzer: ContributorAnalyzer by lazy { ContributorAnalyzer(gitClient) }

        return QodanaCommand().subcommands(
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
        )
    }

    @Test
    fun `scan command exercises the full docker-java surface against jvm-community`(
        @TempDir tempDir: Path,
    ) {
        Assumptions.assumeTrue(
            System.getenv("QODANA_TEST_CONTAINER") == "1",
            "Skipping native scan smoke (set QODANA_TEST_CONTAINER=1)",
        )
        // Fail loudly if the flag is set but Docker isn't reachable, per
        // CLAUDE.md's "tests must never silently skip on missing dependencies".
        // info() is suspend, so wrap in runBlocking.
        try {
            runBlocking { DockerJavaEngine().info() }
        } catch (e: Exception) {
            fail("QODANA_TEST_CONTAINER=1 but Docker is unreachable: ${e.message}")
        }

        // Gradle's test classpath uses unpacked build/resources/test dirs;
        // Path.of(URI) on a file:// URL works. Assert loudly if the test ever
        // runs on a jar'd classpath where this fails silently otherwise.
        val fixture =
            this::class.java.classLoader.getResource("scan-smoke-fixture")
                ?: error("scan-smoke-fixture not on test classpath")
        check(fixture.protocol == "file") {
            "scan-smoke-fixture expected to be on a file:// classpath, got $fixture"
        }
        // Pre-resolve the macOS /var/folders → /private/var/folders symlink so
        // `-i` and `--repository-root` come out of normalizePath() with the
        // same prefix. Otherwise IdeArgBuilder's relativize() generates a
        // navigate-up container-side --project-dir the linter can't lstat.
        val projectDir =
            Files
                .createDirectories(tempDir.resolve("project"))
                .toRealPath()
        Path
            .of(fixture.toURI())
            .toFile()
            .copyRecursively(projectDir.toFile())
        val resultsDir =
            Files
                .createDirectories(tempDir.resolve("results"))
                .toRealPath()

        // ScanCommand.run() always throws ProgramResult(exitCode) on success;
        // existing ScanCommandTest.kt + QodanaCommandTest.kt use this pattern.
        //
        // --repository-root is set explicitly to the same value as -i so the
        // CLI doesn't walk up looking for .git and find this monorepo's root
        // (which would produce a navigate-up relative project dir the linter
        // inside the container can't resolve).
        val ex =
            assertFailsWith<ProgramResult> {
                buildRootCommand().parse(
                    listOf(
                        "scan",
                        "-i",
                        projectDir.toString(),
                        "-o",
                        resultsDir.toString(),
                        "--repository-root",
                        projectDir.toString(),
                    ),
                )
            }
        assertEquals(0, ex.statusCode, "scan exit code; output: $output")

        val sarif = resultsDir.resolve("qodana.sarif.json")
        assertTrue(Files.exists(sarif), "scan must produce SARIF at $sarif")
        val tuples = SarifCompare.normalize(sarif, projectDir)
        assertTrue(tuples.isNotEmpty(), "expected >=1 finding from fixture; got empty SARIF")
        // Stable rule the jvm-community linter fires on the fixture
        // (Hello.equalsBroken uses `==` on String). If the linter version
        // bumps and the rule renames, this assertion's clear failure points
        // at the rename rather than the test going green silently.
        assertTrue(
            tuples.any { it.startsWith("StringEquality|") },
            "expected a StringEquality finding; got $tuples",
        )
    }

    @Test
    fun `containerEngine info and version are reachable under the agent`() =
        runBlocking {
            Assumptions.assumeTrue(
                System.getenv("QODANA_TEST_CONTAINER") == "1",
                "Skipping (set QODANA_TEST_CONTAINER=1)",
            )
            // Drives Info + Version DTO deserialization explicitly so the agent
            // captures them deterministically, not "incidentally" via scan.
            val info = DockerJavaEngine().info()
            assertNotNull(info.version)
        }

    @Test
    fun `view command reads a SARIF file via QodanaSarifService`(
        @TempDir dir: Path,
    ) {
        val sarif = dir.resolve("qodana.sarif.json")
        sarif.writeText(
            """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"test"}},"results":[
                {"ruleId":"R1","level":"warning","message":{"text":"m"},
                 "locations":[{"physicalLocation":{"artifactLocation":{"uri":"a.kt"},
                 "region":{"startLine":1}}}]}
            ]}]}""",
        )
        // view returns normally on success — no ProgramResult thrown.
        buildRootCommand().parse(listOf("view", "-f", sarif.toString()))
        assertTrue(
            output.any { it.contains("R1:") },
            "view should print rule R1; got $output",
        )
    }

    @Test
    fun `pull command pulls an image via docker-java`() {
        Assumptions.assumeTrue(
            System.getenv("QODANA_TEST_CONTAINER") == "1",
            "Skipping (set QODANA_TEST_CONTAINER=1)",
        )
        try {
            runBlocking { DockerJavaEngine().info() }
        } catch (e: Exception) {
            fail("QODANA_TEST_CONTAINER=1 but Docker is unreachable: ${e.message}")
        }
        // Force-remove the image first so PullResponseItem + ProgressDetail
        // streaming actually fires (a cached image short-circuits and the
        // streaming DTOs never deserialize).
        runCatching {
            ProcessBuilder("docker", "image", "rm", "alpine:3.20")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
        // pull returns normally on success.
        buildRootCommand().parse(listOf("pull", "--image", "alpine:3.20"))
    }

    @Test
    fun `show --dir-only exits cleanly on an existing results dir`(
        @TempDir dir: Path,
    ) {
        val projectDir = Files.createDirectories(dir.resolve("project"))
        val resultsDir = Files.createDirectories(dir.resolve("results"))
        // show --dir-only returns normally on success; ShowCommand.openDirectory
        // already swallows xdg-open/open/cmd-start failures so the absence of
        // xdg-utils on headless CI doesn't fail the test.
        buildRootCommand().parse(
            listOf(
                "show",
                "-i",
                projectDir.toString(),
                "-o",
                resultsDir.toString(),
                "--dir-only",
            ),
        )
    }

    @Test
    fun `every docker-java DTO from QD-14728 is reachable for the agent`() {
        // Per QD-14728 ticket text. Class.forName under the agent records the
        // class in reflect-config; pairing this with the smoke tests above
        // means each DTO is captured deterministically, not contingent on
        // which code paths happen to fire at agent capture time.
        // Class locations verified against docker-java-api 3.4.1 jar:
        // command responses live under `.api.command.*`; reusable models live
        // under `.api.model.*`; ProgressDetail is a nested type of ResponseItem.
        val classes =
            listOf(
                "com.github.dockerjava.api.model.PullResponseItem",
                "com.github.dockerjava.api.model.Frame",
                "com.github.dockerjava.api.model.WaitResponse",
                "com.github.dockerjava.api.command.InspectContainerResponse",
                "com.github.dockerjava.api.model.Info",
                "com.github.dockerjava.api.model.Version",
                "com.github.dockerjava.api.command.CreateContainerResponse",
                "com.github.dockerjava.api.command.InspectImageResponse",
                "com.github.dockerjava.api.model.ResponseItem\$ProgressDetail",
                "com.github.dockerjava.api.exception.NotFoundException",
            )
        classes.forEach { Class.forName(it) }
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

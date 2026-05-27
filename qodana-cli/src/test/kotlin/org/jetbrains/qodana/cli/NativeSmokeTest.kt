package org.jetbrains.qodana.cli

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.qodana.cli.command.ClocCommand
import org.jetbrains.qodana.cli.command.ContributorsCommand
import org.jetbrains.qodana.cli.command.InitCommand
import org.jetbrains.qodana.cli.command.PullCommand
import org.jetbrains.qodana.cli.command.QodanaCommand
import org.jetbrains.qodana.cli.command.ScanCommand
import org.jetbrains.qodana.cli.command.SendCommand
import org.jetbrains.qodana.cli.command.SendFakeHttpTransport
import org.jetbrains.qodana.cli.command.SendFixedTokenStore
import org.jetbrains.qodana.cli.command.SendTestTerminal
import org.jetbrains.qodana.cli.command.ShowCommand
import org.jetbrains.qodana.cli.command.ViewCommand
import org.jetbrains.qodana.cloudclient.MockQDCloudHttpClient
import org.jetbrains.qodana.cloudclient.QDCloudResponse
import org.jetbrains.qodana.cloudclient.respond
import org.jetbrains.qodana.cloudclient.s3.QDCloudS3Client
import org.jetbrains.qodana.core.fs.NioFileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.jetbrains.qodana.engine.contributors.ContributorAnalyzer
import org.jetbrains.qodana.engine.docker.DockerJavaEngine
import org.jetbrains.qodana.engine.env.RuntimeEnvironment
import org.jetbrains.qodana.engine.git.SystemGitClient
import org.jetbrains.qodana.engine.http.OkHttpTransport
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.publisher.PublisherAdapter
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.reportconverter.ReportConverterAdapter
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.http.HttpClient
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
 * reflective surface the native binary will hit at runtime:
 *
 *   - `--version`, `--help`
 *   - `<subcommand> --help` for each registered subcommand
 *   - `init`, `view`, `show`, `send` (no Docker required)
 *   - `scan`, `pull`, `info`, `version` (require a running Docker daemon —
 *     tagged `@Tag("docker")`; executed by the `parityTest` Gradle task)
 *
 * Under `-Pagent`, this captures reachability metadata for Clikt's
 * option/argument reflection, every runtime serde DTO from docker-java /
 * Apache HttpClient 5 / QDCloudClient, and the Publisher S3 upload chain.
 * QD-14728 extended scope from Phase A's `--help`/`--version`/`init` to the
 * full set of runtime commands.
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

        // Heavy — kept lazy to mirror Main.kt. Lazy values still gate
        // --help/--version/init flows from constructing anything heavy.
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

    @Test
    @Tag("docker")
    fun `scan command exercises the full docker-java surface against jvm-community`(
        @TempDir tempDir: Path,
    ) {
        // @Tag("docker") routes this test through the parityTest Gradle
        // task, which is the only task that runs it. Fail loudly if Docker
        // isn't reachable, per CLAUDE.md "tests must never silently skip".
        try {
            runBlocking { DockerJavaEngine().info() }
        } catch (e: Exception) {
            fail("@Tag(\"docker\") test ran but Docker is unreachable: ${e.message}")
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
    @Tag("docker")
    fun `containerEngine info and version are reachable under the agent`() =
        runBlocking {
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
    @Tag("docker")
    fun `imageExists exercises InspectImageResponse and NotFoundException`() =
        runBlocking {
            try {
                DockerJavaEngine().info()
            } catch (e: Exception) {
                fail("@Tag(\"docker\") test ran but Docker is unreachable: ${e.message}")
            }
            // Pull alpine first so the existence check actually deserialises
            // the full InspectImageResponse DTO (Codex critical #3: under the
            // agent this records every accessed field, replacing the prior
            // bare-name entry with the full reachable-fields metadata).
            val engine = DockerJavaEngine()
            engine.pull("alpine:3.20") { /* ignore stream */ }
            assertTrue(
                engine.imageExists("alpine:3.20"),
                "alpine:3.20 should exist after pull (drives InspectImageResponse deserialization)",
            )
            // Force the NotFoundException code path (Codex warning): the
            // exception type is thrown by docker-java when the image cannot
            // be resolved, exercising its real deserialization path so the
            // agent records the proper reflect-config entry.
            assertTrue(
                !engine.imageExists("definitely-missing-image-tag-qd14728:0.0.0"),
                "missing image should return false (drives NotFoundException path)",
            )
        }

    @Test
    @Tag("docker")
    fun `pull command pulls an image via docker-java`() {
        try {
            runBlocking { DockerJavaEngine().info() }
        } catch (e: Exception) {
            fail("@Tag(\"docker\") test ran but Docker is unreachable: ${e.message}")
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

    /**
     * Exercises the full `send` path end-to-end without touching real cloud:
     *  - OkHttp/Jackson path (CloudClient token validation) via SendFakeHttpTransport
     *  - kotlinx.serialization path (QDCloudClient report upload) via MockQDCloudHttpClient
     *  - S3 PUT path (QDCloudS3ClientImpl) via a local com.sun.net.httpserver.HttpServer
     *
     * Running under the GraalVM tracing agent captures all Jackson + kotlinx.serialization
     * reflection/serde metadata needed for the native binary.
     */
    @Test
    fun `send exercises the full QDCloudClient and Publisher serialisation chain`(
        @TempDir dir: Path,
    ) {
        val projectDir = Files.createDirectories(dir.resolve("project"))
        val resultsDir = Files.createDirectories(dir.resolve("results"))
        resultsDir.resolve("qodana.sarif.json").writeText(
            """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"test"}},"results":[]}]}""",
        )

        // Bind explicitly to 127.0.0.1 (not 0.0.0.0/InetSocketAddress(0))
        // so IPv4/IPv6 hostname resolution can't cause flaky test failures
        // when "localhost" prefers ::1 but the server only listened on 0.0.0.0.
        val s3Server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s3Server.createContext("/upload/sarif") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        s3Server.start()
        try {
            val mockCloudClient = buildSendMockCloudClient(s3Port = s3Server.address.port)
            val publisherAdapter =
                PublisherAdapter(
                    httpClient = mockCloudClient,
                    s3Client = QDCloudS3Client(HttpClient.newHttpClient()),
                )
            val sendTerminal = SendTestTerminal(isInteractive = false)

            SendCommand(
                reportPublisher = ReportPublishUseCase(publisherAdapter),
                terminal = sendTerminal,
                getEnv = { key ->
                    when (key) {
                        "QODANA_TOKEN" -> "test-token"
                        "QODANA_ENDPOINT" -> "https://qodana.cloud"
                        else -> null
                    }
                },
                tokenStore = SendFixedTokenStore(null),
                httpTransport = buildSendFakeHttpTransport(),
                runtimeEnvironmentDetector = { RuntimeEnvironment.HOST },
            ).parse(listOf("-i", projectDir.toString(), "-o", resultsDir.toString()))

            // SendCommand.run() returns normally on success — no ProgramResult thrown
            assertTrue(
                sendTerminal.messages.any { it.contains("Report published:") },
                "send should print 'Report published:'; got: ${sendTerminal.messages}",
            )
            assertTrue(
                mockCloudClient.requestsCount >= 3,
                "expected >= 3 MockQDCloudHttpClient requests, got ${mockCloudClient.requestsCount}",
            )
        } finally {
            s3Server.stop(0)
        }
    }

    /**
     * Configures a [MockQDCloudHttpClient] with canned responses for the three
     * QDCloudClient requests the Publisher makes: api/versions, reports/ (startUpload),
     * and reports/{id}/finish/ (finishUpload).  S3 upload goes to the local [s3Port].
     */
    private fun buildSendMockCloudClient(s3Port: Int): MockQDCloudHttpClient {
        val client = MockQDCloudHttpClient.empty()
        // QDCloudByFrontendEnvironment: GET api/versions on the endpoint host
        client.respond("https://qodana.cloud", "api/versions") { _ ->
            QDCloudResponse.Success(
                """{"api":{"versions":[{"version":"1.1","url":"https://cloud.api"}]},""" +
                    """"linters":{"versions":[{"version":"1.0","url":"https://linters.api"}]}}""",
            )
        }
        // Publisher.startUpload: POST reports/
        client.respond("https://cloud.api", "reports/") { _ ->
            QDCloudResponse.Success(
                """{"reportId":"test-report-id",""" +
                    """"fileLinks":{"qodana.sarif.json":"http://127.0.0.1:$s3Port/upload/sarif"},""" +
                    """"langsRequired":false}""",
            )
        }
        // Publisher.finishUpload: POST reports/test-report-id/finish/
        client.respond("https://cloud.api", "reports/test-report-id/finish/") { _ ->
            QDCloudResponse.Success(
                """{"token":"report-token-123","url":"https://cloud.api/report/test-report-id"}""",
            )
        }
        return client
    }

    /** OkHttp/Jackson path: CloudClient.fetchEndpoints() + project token validation. */
    private fun buildSendFakeHttpTransport(): SendFakeHttpTransport =
        SendFakeHttpTransport(
            mapOf(
                "https://qodana.cloud/api/versions" to
                    HttpResponse(
                        200,
                        """{"api":{"versions":[{"version":"1.1","url":"https://cloud.api"}]},""" +
                            """"linters":{"versions":[{"version":"1.0","url":"https://linters.api"}]}}""",
                    ),
                "https://cloud.api/projects" to
                    HttpResponse(200, """{"id":"proj1","organizationId":"org1","name":"sample-project"}"""),
            ),
        )

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

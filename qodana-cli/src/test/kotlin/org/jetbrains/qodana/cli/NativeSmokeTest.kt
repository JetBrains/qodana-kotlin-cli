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
 * Mirrors Main.kt's subcommand tree so a `-Pagent` run captures the full
 * reflective surface the native binary hits at runtime (Clikt option classes,
 * docker-java / HttpClient5 / QDCloudClient serde DTOs, Publisher S3 chain).
 * Docker-touching cases are tagged `@Tag("docker")`; the rest run under `test`.
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
        val processRunner = SystemProcessRunner()
        val gitClient = SystemGitClient(processRunner)

        // Lazy to mirror Main.kt so --help/--version/init don't drag in
        // OkHttp/docker-java/Jackson construction.
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
        requireDocker()

        val fixture =
            this::class.java.classLoader.getResource("scan-smoke-fixture")
                ?: error("scan-smoke-fixture not on test classpath")
        check(fixture.protocol == "file") {
            "scan-smoke-fixture expected to be on a file:// classpath, got $fixture"
        }
        // toRealPath() avoids the macOS /var/folders → /private/var/folders
        // mismatch that makes IdeArgBuilder's relativize() generate a
        // navigate-up container-side --project-dir.
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

        // Pin --repository-root to projectDir so the CLI doesn't walk up,
        // find this monorepo's .git, and emit a navigate-up project dir.
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
        assertTrue(
            tuples.any { it.startsWith("StringEquality|") },
            "expected a StringEquality finding; got $tuples",
        )
    }

    private fun requireDocker() {
        try {
            runBlocking { DockerJavaEngine().info() }
        } catch (e: Exception) {
            fail("@Tag(\"docker\") test ran but Docker is unreachable: ${e.message}")
        }
    }

    // Drives Info + Version DTO deserialization deterministically (not just
    // incidentally via scan), so the agent captures both regardless.
    @Test
    @Tag("docker")
    fun `containerEngine info and version are reachable under the agent`() =
        runBlocking {
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
        buildRootCommand().parse(listOf("view", "-f", sarif.toString()))
        assertTrue(
            output.any { it.contains("R1:") },
            "view should print rule R1; got $output",
        )
    }

    // Drives InspectImageResponse + NotFoundException through their real
    // deserialization paths so the agent gets full reachable-fields metadata
    // rather than the bare-name entries Class.forName alone would produce.
    @Test
    @Tag("docker")
    fun `imageExists exercises InspectImageResponse and NotFoundException`() =
        runBlocking {
            requireDockerSuspend()
            val engine = DockerJavaEngine()
            engine.pull("alpine:3.20") { /* ignore stream */ }
            assertTrue(engine.imageExists("alpine:3.20"))
            assertTrue(!engine.imageExists("definitely-missing-image-tag-qd14728:0.0.0"))
        }

    private suspend fun requireDockerSuspend() {
        try {
            DockerJavaEngine().info()
        } catch (e: Exception) {
            fail("@Tag(\"docker\") test ran but Docker is unreachable: ${e.message}")
        }
    }

    @Test
    @Tag("docker")
    fun `pull command pulls an image via docker-java`() {
        requireDocker()
        // Force-remove first so PullResponseItem + ProgressDetail streaming
        // fires (a cached image short-circuits and the DTOs never deserialize).
        runCatching {
            ProcessBuilder("docker", "image", "rm", "alpine:3.20")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
        buildRootCommand().parse(listOf("pull", "--image", "alpine:3.20"))
    }

    // ShowCommand.openDirectory swallows xdg-open/open/cmd-start failures,
    // so the absence of xdg-utils on headless CI doesn't fail this test.
    @Test
    fun `show --dir-only exits cleanly on an existing results dir`(
        @TempDir dir: Path,
    ) {
        val projectDir = Files.createDirectories(dir.resolve("project"))
        val resultsDir = Files.createDirectories(dir.resolve("results"))
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

    // Class.forName under the agent records the class in reflect-config,
    // giving every docker-java DTO from the ticket deterministic baseline
    // coverage independent of which code paths happen to fire at capture.
    @Test
    fun `every docker-java DTO from QD-14728 is reachable for the agent`() {
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
    fun `per-subcommand help exits cleanly for every subcommand`() {
        for (sub in SUBCOMMAND_NAMES) {
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
        val projectDir = Files.createDirectories(dir.resolve("project"))
        projectDir.resolve("build.gradle.kts").writeText("// fixture for native smoke test")

        buildRootCommand().parse(listOf("init", "-i", projectDir.toString()))

        val yaml = projectDir.resolve("qodana.yaml")
        assertTrue(Files.exists(yaml), "init should create qodana.yaml")
        val content = Files.readString(yaml)
        assertContains(content, "linter:", message = "qodana.yaml should declare a linter")
    }

    // Clikt's "no such option" error formatter reflects on the option class
    // — a common MissingReflectionRegistrationError surface in the native
    // binary, so this path needs to run under the agent.
    @Test
    fun `scan with unknown flag raises a Clikt UsageError`() {
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

        // In-place update and rewrite are both valid; the invariant is that
        // "linter: stale" is gone.
        val content = Files.readString(yaml)
        assertTrue(!content.contains("linter: stale"), "stale linter should have been replaced: $content")
    }

    // Drives the three transports `send` touches — SendFakeHttpTransport
    // (CloudClient/OkHttp+Jackson), MockQDCloudHttpClient (QDCloudClient/
    // kotlinx.serialization), and a local HttpServer (S3 PUT) — without
    // hitting real cloud, so the agent records each serde path.
    @Test
    fun `send exercises the full QDCloudClient and Publisher serialisation chain`(
        @TempDir dir: Path,
    ) {
        val projectDir = Files.createDirectories(dir.resolve("project"))
        val resultsDir = Files.createDirectories(dir.resolve("results"))
        resultsDir.resolve("qodana.sarif.json").writeText(
            """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"test"}},"results":[]}]}""",
        )

        // 127.0.0.1 (not localhost / 0.0.0.0) avoids the IPv4/IPv6 race
        // where ::1 resolution wins but the server only bound IPv4.
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

    // Canned QDCloudClient responses matching the paths Publisher hits:
    // GET api/versions → POST reports/ (startUpload) → POST reports/{id}/finish/
    // (finishUpload). S3 fileLink points at the local [s3Port].
    private fun buildSendMockCloudClient(s3Port: Int): MockQDCloudHttpClient {
        val client = MockQDCloudHttpClient.empty()
        client.respond("https://qodana.cloud", "api/versions") { _ ->
            QDCloudResponse.Success(
                """{"api":{"versions":[{"version":"1.1","url":"https://cloud.api"}]},""" +
                    """"linters":{"versions":[{"version":"1.0","url":"https://linters.api"}]}}""",
            )
        }
        client.respond("https://cloud.api", "reports/") { _ ->
            QDCloudResponse.Success(
                """{"reportId":"test-report-id",""" +
                    """"fileLinks":{"qodana.sarif.json":"http://127.0.0.1:$s3Port/upload/sarif"},""" +
                    """"langsRequired":false}""",
            )
        }
        client.respond("https://cloud.api", "reports/test-report-id/finish/") { _ ->
            QDCloudResponse.Success(
                """{"token":"report-token-123","url":"https://cloud.api/report/test-report-id"}""",
            )
        }
        return client
    }

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

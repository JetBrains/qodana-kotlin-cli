package org.jetbrains.qodana.cli

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
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
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.jetbrains.qodana.engine.publisher.PublisherAdapter
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.reportconverter.ReportConverterAdapter
import org.jetbrains.qodana.engine.scan.IdeProductDiscovery
import org.jetbrains.qodana.engine.startup.IdeInstaller
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

    // QD-14960: the native scan path deserializes these flat @JsonProperty
    // data classes via Jackson `readValue`:
    //   - IdeProductInfoJson through IdeProductDiscovery.guessProduct (the
    //     sanctioned QODANA_DIST path; the dogfood InvalidDefinitionException).
    //   - Product/ReleaseInfo/ReleaseDownloadInfo through
    //     IdeInstaller.getProductByCode (the feed-install path, hit by
    //     `scan --linter X` when no dist is baked).
    // Driving the real deserialization here lets the tracing agent record full
    // reachable-fields metadata. Every fixture OMITS optional fields so the
    // agent ALWAYS records the Kotlin synthetic default-arg
    // (`...,int,DefaultConstructorMarker`) constructor that jackson-module-kotlin
    // invokes whenever a JSON field is absent — and real product-info.json /
    // feed entries routinely omit fields (stable builds have no versionSuffix).
    // This is host-independent: the dist-binary LAYOUT branches on os.name (so
    // guessProduct finds the binary on any host), but the JSON field coverage
    // does not. No Class.forName baseline: real deserialization covers all four
    // classes (Class.forName yields inferior bare-name entries anyway).
    @Test
    fun `native scan-path Jackson models are reachable for the agent`(
        @TempDir tempDir: Path,
    ) {
        val os = System.getProperty("os.name").lowercase()
        val isMac = "mac" in os || "darwin" in os
        val isWindows = "win" in os

        // guessProduct branches on os.name: macOS reads MacOS/<ide> +
        // Resources/product-info.json; Windows wants bin/<ide>64.exe; Linux
        // wants bin/<ide>. Lay out the binary to match the host so guessProduct
        // finds it everywhere a contributor runs `./gradlew :qodana-cli:test`.
        // versionSuffix is OMITTED on every host so IdeProductInfoJson's
        // synthetic default-arg constructor is exercised regardless of OS.
        val dist = Files.createDirectories(tempDir.resolve("dist"))
        val productInfo = """{"version":"2025.3","buildNumber":"253.1234","productCode":"IU"}"""
        if (isMac) {
            Files.createDirectories(dist.resolve("MacOS")).resolve("idea").writeText("#!/bin/sh\n")
            Files.createDirectories(dist.resolve("Resources")).resolve("product-info.json").writeText(productInfo)
        } else {
            val binName = if (isWindows) "idea64.exe" else "idea"
            Files.createDirectories(dist.resolve("bin")).resolve(binName).writeText("#!/bin/sh\n")
            dist.resolve("product-info.json").writeText(productInfo)
        }
        val product = IdeProductDiscovery.guessProduct(dist, NioFileSystem())
        assertEquals("IU", product.ideCode)
        assertEquals("QDJVM", product.code, "toQodanaCode must map IU -> QDJVM")
        assertEquals("2025.3", product.version)
        assertEquals("253.1234", product.build)
        assertEquals("idea", product.baseScriptName)
        assertTrue(!product.isEap, "omitted versionSuffix must default to non-EAP")
        // `name` is deliberately NOT asserted: both JVM and Android linter
        // properties carry productInfoJsonCode "IU", so findByProductInfoCode
        // is order-dependent — a name assertion would be brittle.

        // Feed deserialization. Every entry omits optional fields so the agent
        // records the synthetic default-arg constructor of each feed DTO:
        //   - Product: second entry omits "Releases".
        //   - ReleaseInfo: "minimal" release omits everything but Date/Type.
        //   - ReleaseDownloadInfo: omits "Size"/"ChecksumLink".
        val feed =
            """
            [
              {"Code":"IIU","Releases":[
                {"Date":"2025-03-01","Type":"release","Version":"2025.3.1","MajorVersion":"2025.3",
                 "Build":"253.1234","PrintableReleaseType":"Stable",
                 "Downloads":{"linux":{"Link":"https://example.com/idea.tar.gz"}}},
                {"Date":"2025-02-01","Type":"eap"}
              ]},
              {"Code":"GO"}
            ]
            """.trimIndent()
        // getProductByCode is a plain fun that runBlocks internally — call it
        // directly, NOT inside another runBlocking.
        val installer = IdeInstaller(fixedGetHttp(feed), NioFileSystem(), terminal)
        val feedProduct = installer.getProductByCode("IIU")
        assertNotNull(feedProduct)
        assertEquals("IIU", feedProduct.code)
        assertEquals(2, feedProduct.releases.size)
        val fullRelease = feedProduct.releases.first { it.type == "release" }
        assertEquals("253.1234", fullRelease.build)
        assertNotNull(fullRelease.downloads?.get("linux"))
        val minimalRelease = feedProduct.releases.first { it.type == "eap" }
        assertEquals(null, minimalRelease.downloads, "minimal release exercises default-valued fields")
        // The "GO" entry omits Releases -> Product's synthetic default-arg ctor.
        val minimalProduct = installer.getProductByCode("GO")
        assertNotNull(minimalProduct)
        assertEquals(emptyList(), minimalProduct.releases, "omitted Releases must default to empty")
    }

    @Test
    fun `qodana yaml model graph is reachable for the agent`() {
        val yaml =
            """
            profile:
              name: starter
            script:
              name: custom
            include:
              - name: included-rule
            exclude:
              - name: excluded-rule
            plugins:
              - {}
            dotnet:
              solution: app.sln
            php: {}
            cpp:
              buildSystem: cmake
            failureConditions:
              severityThresholds:
                critical: 1
              testCoverageThresholds:
                total: 80
            licenseRules:
              - keys: ["MIT"]
            dependencyIgnores:
              - {}
            dependencyOverrides:
              - name: override
                version: "1.0"
            projectLicenses:
              - key: Apache-2.0
            customDependencies:
              - name: dep
                version: "2.0"
            dependencySbomExclude:
              - {}
            modulesToAnalyze:
              - {}
            coverage: {}
            hardcodedPasswords: {}
            """.trimIndent()

        val config: org.jetbrains.qodana.core.model.QodanaYaml = YAML_MAPPER.readValue(yaml)
        assertNotNull(config)
    }

    // QD-14960 regression guard: a real test (not just a capture driver) that
    // FAILS if a future metadata regen drops these classes — or their required
    // constructors — from the committed reflect-config.json. JVM deserialization
    // passes even with broken native metadata (reflection is free on the JVM),
    // so this is the test that actually catches the native-image gap recurring.
    // jackson-module-kotlin invokes the synthetic `...,int,DefaultConstructorMarker`
    // constructor whenever a JSON field is absent, so each class with optional
    // fields MUST register that variant, not only the full-arity one.
    @Test
    fun `committed reflect-config registers the native scan-path Jackson models`() {
        val reflectConfig = Path.of(REFLECT_CONFIG_PATH)
        assertTrue(
            Files.exists(reflectConfig),
            "expected $reflectConfig to exist; regenerate via `./gradlew :qodana-cli:metadataCopy`",
        )

        val entries: List<Map<String, Any?>> =
            ObjectMapper().readValue(reflectConfig.toFile(), object : TypeReference<List<Map<String, Any?>>>() {})
        val byName = entries.filter { it["name"] is String }.associateBy { it["name"] as String }

        val problems = mutableListOf<String>()
        for ((fqcn, requiredCtors) in requiredNativeScanPathCtors()) {
            val entry = byName[fqcn]
            if (entry == null) {
                problems.add("$fqcn: not registered")
                continue
            }
            if (entry["allDeclaredFields"] != true) {
                problems.add("$fqcn: allDeclaredFields must be true (Jackson reads every field)")
            }
            if (entry["allDeclaredConstructors"] == true) {
                continue
            }

            @Suppress("UNCHECKED_CAST")
            val methods = entry["methods"] as? List<Map<String, Any?>> ?: emptyList()
            val ctorParamLists =
                methods
                    .filter { it["name"] == "<init>" }
                    .map { (it["parameterTypes"] as? List<*>)?.map(Any?::toString) ?: emptyList() }
                    .toSet()
            for (ctor in requiredCtors) {
                if (ctor !in ctorParamLists) {
                    problems.add("$fqcn: missing <init>(${ctor.joinToString(", ")})")
                }
            }
        }
        assertTrue(
            problems.isEmpty(),
            "reflect-config.json native-scan-path Jackson models are incomplete:\n" +
                problems.joinToString("\n") { "  $it" } +
                "\nRe-run agent capture (`./gradlew -Pagent :qodana-cli:test :qodana-cli:parityTest " +
                "--rerun-tasks` then `:qodana-cli:metadataCopy`); on a stale toolchain, transcribe the " +
                "agent's captured `<init>` lists (incl. the DefaultConstructorMarker variant) by hand.",
        )
    }

    @Test
    fun `committed reflect-config registers qodana yaml Jackson models`() {
        val reflectConfig = Path.of(REFLECT_CONFIG_PATH)
        assertTrue(
            Files.exists(reflectConfig),
            "expected $reflectConfig to exist; regenerate via `./gradlew :qodana-cli:metadataCopy`",
        )

        val entries: List<Map<String, Any?>> =
            ObjectMapper().readValue(reflectConfig.toFile(), object : TypeReference<List<Map<String, Any?>>>() {})
        val byName = entries.filter { it["name"] is String }.associateBy { it["name"] as String }

        val problems = mutableListOf<String>()
        for (fqcn in requiredQodanaYamlModelClasses()) {
            val entry = byName[fqcn]
            if (entry == null) {
                problems.add("$fqcn: not registered")
                continue
            }
            if (entry["allDeclaredFields"] != true) {
                problems.add("$fqcn: allDeclaredFields must be true (Jackson reads every field)")
            }
            if (entry["allDeclaredConstructors"] != true) {
                problems.add("$fqcn: allDeclaredConstructors must be true (constructor drift guard)")
            }
            if (entry["queryAllDeclaredConstructors"] != true) {
                problems.add("$fqcn: queryAllDeclaredConstructors must be true (native constructor lookup)")
            }
            if (entry["queryAllDeclaredMethods"] != true) {
                problems.add("$fqcn: queryAllDeclaredMethods must be true (native method lookup)")
            }
        }
        assertTrue(
            problems.isEmpty(),
            "reflect-config.json qodana-yaml Jackson models are incomplete:\n" +
                problems.joinToString("\n") { "  $it" } +
                "\nRe-run agent capture (`./gradlew -Pagent :qodana-cli:test --rerun-tasks` then " +
                "`:qodana-cli:metadataCopy`) and keep the full Kotlin default-argument constructors.",
        )
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
        val YAML_MAPPER = YAMLMapper().registerModule(kotlinModule())

        // Module-relative; tests run with the qodana-cli dir as cwd (same
        // convention as MetadataHygieneTest).
        const val REFLECT_CONFIG_PATH =
            "src/main/resources/META-INF/native-image/org.jetbrains.qodana/qodana-cli/reflect-config.json"

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

// QD-14960: each native-scan-path Jackson model -> the <init> parameter-type
// lists that MUST be in reflect-config. The `...,int,DefaultConstructorMarker`
// variant is the synthetic default-arg ctor jackson-module-kotlin invokes when
// a JSON field is absent (e.g. a stable product-info.json with no versionSuffix);
// without it the native binary throws MissingReflectionRegistrationError.
private fun requiredNativeScanPathCtors(): Map<String, List<List<String>>> {
    val dcm = "kotlin.jvm.internal.DefaultConstructorMarker"
    val str = "java.lang.String"
    return mapOf(
        "org.jetbrains.qodana.engine.scan.IdeProductInfoJson" to
            listOf(
                listOf(str, str, str, str),
                listOf(str, str, str, str, "int", dcm),
            ),
        "org.jetbrains.qodana.engine.startup.Product" to
            listOf(
                listOf(str, "java.util.List"),
                listOf(str, "java.util.List", "int", dcm),
            ),
        "org.jetbrains.qodana.engine.startup.ReleaseInfo" to
            listOf(
                listOf(str, str, "java.util.Map", str, str, str, str),
                listOf(str, str, "java.util.Map", str, str, str, str, "int", dcm),
            ),
        "org.jetbrains.qodana.engine.startup.ReleaseDownloadInfo" to
            listOf(
                listOf(str, "long", str),
                listOf(str, "long", str, "int", dcm),
            ),
    )
}

private fun requiredQodanaYamlModelClasses(): List<String> =
    listOf(
        "org.jetbrains.qodana.core.model.InspectScope",
        "org.jetbrains.qodana.core.model.QodanaYaml",
        "org.jetbrains.qodana.core.model.YamlCoverage",
        "org.jetbrains.qodana.core.model.YamlCoverageThresholds",
        "org.jetbrains.qodana.core.model.YamlCpp",
        "org.jetbrains.qodana.core.model.YamlCustomDependency",
        "org.jetbrains.qodana.core.model.YamlDependencyIgnore",
        "org.jetbrains.qodana.core.model.YamlDependencyOverride",
        "org.jetbrains.qodana.core.model.YamlDotNet",
        "org.jetbrains.qodana.core.model.YamlFailureConditions",
        "org.jetbrains.qodana.core.model.YamlHardcodedPasswords",
        "org.jetbrains.qodana.core.model.YamlLicenseOverride",
        "org.jetbrains.qodana.core.model.YamlLicenseRule",
        "org.jetbrains.qodana.core.model.YamlModuleToAnalyze",
        "org.jetbrains.qodana.core.model.YamlPhp",
        "org.jetbrains.qodana.core.model.YamlPlugin",
        "org.jetbrains.qodana.core.model.YamlProfile",
        "org.jetbrains.qodana.core.model.YamlScript",
        "org.jetbrains.qodana.core.model.YamlSeverityThresholds",
    )

// A minimal HttpTransport that returns [body] for every GET and empty 200s
// otherwise — enough to drive IdeInstaller.getProductByCode's feed fetch under
// the agent without a real network. (QD-14960.)
private fun fixedGetHttp(body: String): HttpTransport =
    object : HttpTransport {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ) = HttpResponse(200, body)

        override suspend fun post(
            url: String,
            body: ByteArray,
            contentType: String,
            headers: Map<String, String>,
        ) = HttpResponse(200, "")

        override suspend fun download(
            url: String,
            target: Path,
            headers: Map<String, String>,
        ) = Unit

        override suspend fun uploadMultipart(
            url: String,
            parts: List<MultipartPart>,
            headers: Map<String, String>,
        ) = HttpResponse(200, "")
    }

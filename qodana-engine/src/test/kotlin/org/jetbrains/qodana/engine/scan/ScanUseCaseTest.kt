package org.jetbrains.qodana.engine.scan

import com.jetbrains.qodana.sarif.SarifUtil
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.RunningProcess
import org.jetbrains.qodana.core.port.SarifService
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.cloud.CloudClient
import org.jetbrains.qodana.engine.cloud.LicenseData
import org.jetbrains.qodana.engine.cloud.LicenseValidator
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.engine.port.ContainerEngine
import org.jetbrains.qodana.engine.port.ContainerEngineInfo
import org.jetbrains.qodana.engine.port.EngineType
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.jetbrains.qodana.engine.port.ReportConverter
import org.jetbrains.qodana.engine.port.ReportPublisher
import org.jetbrains.qodana.engine.port.GitClient
import org.jetbrains.qodana.engine.report.ReportProcessor
import org.jetbrains.qodana.engine.report.ReportPublishUseCase
import org.jetbrains.qodana.engine.startup.PrepareHost
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanUseCaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var projectDir: Path
    private lateinit var resultsDir: Path
    private lateinit var cacheDir: Path
    private lateinit var reportDir: Path

    private lateinit var fakeTerminal: ScanFakeTerminal

    // Recording ports to detect which scan path was taken
    private lateinit var recordingProcessRunner: RecordingProcessRunner
    private lateinit var recordingContainerEngine: RecordingContainerEngine
    private lateinit var recordingLicenseHttp: RecordingHttpTransport
    private lateinit var recordingPublisher: RecordingReportPublisher

    @BeforeEach
    fun setUp() {
        projectDir = tempDir.resolve("project")
        resultsDir = tempDir.resolve("results")
        cacheDir = tempDir.resolve("cache")
        reportDir = tempDir.resolve("report")

        Files.createDirectories(projectDir)
        Files.createDirectories(resultsDir)
        Files.createDirectories(cacheDir)
        Files.createDirectories(reportDir)

        // Write minimal qodana.yaml so EffectiveConfig.load() finds it
        Files.writeString(projectDir.resolve("qodana.yaml"), "version: \"1.0\"\n")

        // Write a minimal SARIF so ReportProcessor can read it
        Files.writeString(
            resultsDir.resolve("qodana.sarif.json"),
            """{"runs": [{"results": []}]}""",
        )

        fakeTerminal = ScanFakeTerminal()
        recordingProcessRunner = RecordingProcessRunner()
        recordingContainerEngine = RecordingContainerEngine()
        recordingLicenseHttp = RecordingHttpTransport()
        recordingPublisher = RecordingReportPublisher()
    }

    private fun scanPaths() = ScanPaths(
        projectDir = projectDir,
        resultsDir = resultsDir,
        cacheDir = cacheDir,
        reportDir = reportDir,
    )

    private fun buildNativeScan(): NativeScan {
        val isMac = System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it }
        val ideBinary = if (isMac) "MacOS/idea" else "bin/idea"
        val productInfoPath = if (isMac) "Resources/product-info.json" else "product-info.json"
        val fs = StubFileSystem(
            existingPaths = setOf(ideBinary),
            fileContents = mapOf(
                productInfoPath to """{"version":"2025.3","buildNumber":"253.12345","productCode":"IU","versionSuffix":""}""",
            ),
        )
        return NativeScan(recordingProcessRunner, fs)
    }

    private fun buildContainerScan(): ContainerScan {
        return ContainerScan(recordingContainerEngine, fakeTerminal)
    }

    private fun buildLicenseValidator(): LicenseValidator {
        return LicenseValidator(recordingLicenseHttp, buildCloudClient())
    }

    private fun buildCloudClient(): CloudClient {
        val http = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) =
                HttpResponse(
                    200,
                    """
                        {
                          "api": {
                            "versions": [
                              {"version":"1.1","url":"https://cloud.example.com"}
                            ]
                          },
                          "linters": {
                            "versions": [
                              {"version":"1.0","url":"https://linters.example.com"}
                            ]
                          }
                        }
                    """.trimIndent()
                )
            override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>) =
                HttpResponse(200, "{}")
            override suspend fun download(url: String, target: Path, headers: Map<String, String>) {}
            override suspend fun uploadMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>) =
                HttpResponse(200, "{}")
        }
        return CloudClient(http = http, endpoint = "https://qodana.cloud", token = "fake-token")
    }

    private fun buildReportPublishUseCase(): ReportPublishUseCase {
        return ReportPublishUseCase(recordingPublisher)
    }

    private fun buildReportProcessor(): ReportProcessor {
        return ReportProcessor(ScanFakeSarifService(), ScanFakeReportConverter())
    }

    private fun buildScanUseCase(
        reportProcessor: ReportProcessor = buildReportProcessor(),
        licenseValidator: LicenseValidator? = buildLicenseValidator(),
        reportPublisher: ReportPublishUseCase? = buildReportPublishUseCase(),
        gitClient: GitClient? = null,
    ): ScanUseCase {
        val fs = StubFileSystem()
        val prepareHost = PrepareHost(fs, fakeTerminal)
        return ScanUseCase(
            prepareHost = prepareHost,
            nativeScan = buildNativeScan(),
            containerScan = buildContainerScan(),
            reportProcessor = reportProcessor,
            reportPublisher = reportPublisher,
            licenseValidator = licenseValidator,
            codeClimateExporter = null,
            bitBucketExporter = null,
            gitClient = gitClient,
            terminal = fakeTerminal,
        )
    }

    private fun buildContext(
        nativeMode: Boolean = true,
        token: String? = null,
        licenseOnlyToken: String? = null,
    ): ScanContext {
        return ScanContext(
            paths = scanPaths(),
            auth = AuthContext(
                token = token,
                endpoint = "https://qodana.cloud",
                licenseOnlyToken = licenseOnlyToken,
            ),
            runtime = RuntimeContext(ideDir = if (nativeMode) tempDir.resolve("ide") else null),
            ci = CiContext(),
            report = ReportOptions(saveReport = false),
            docker = DockerOptions(image = "jetbrains/qodana:latest"),
            nativeMode = nativeMode,
        )
    }

    @Test
    fun `native mode delegates to nativeScan`() = runTest {
        val useCase = buildScanUseCase()

        useCase.run(buildContext(nativeMode = true))

        assertTrue(recordingProcessRunner.started, "nativeScan should have invoked the process runner")
        assertFalse(recordingContainerEngine.created, "containerScan should NOT have been invoked")
    }

    @Test
    fun `container mode delegates to containerScan`() = runTest {
        val useCase = buildScanUseCase()

        useCase.run(buildContext(nativeMode = false))

        assertTrue(recordingContainerEngine.created, "containerScan should have invoked the container engine")
        assertFalse(recordingProcessRunner.started, "nativeScan should NOT have been invoked")
    }

    @Test
    fun `returns report processor exit code`() = runTest {
        // Write a SARIF file with 2 problems; without fail-threshold this should still be SUCCESS
        val sarifContent = """
            {
              "runs": [
                {
                  "results": [
                    {"ruleId": "rule1"},
                    {"ruleId": "rule2"}
                  ]
                }
              ]
            }
        """.trimIndent()
        Files.writeString(resultsDir.resolve("qodana.sarif.json"), sarifContent)

        val useCase = buildScanUseCase()
        val result = useCase.run(buildContext(nativeMode = true))

        assertEquals(ExitCode.SUCCESS.code, result)
    }

    @Test
    fun `license validation called when token present`() = runTest {
        // Set up the license HTTP to return a valid response
        recordingLicenseHttp.responseBody = """{
            "licenseId": "test",
            "licenseKey": "key",
            "expirationDate": "2099-01-01",
            "projectIdHash": "hash1",
            "organisationIdHash": "hash2",
            "licensePlan": "ULTIMATE_PLUS"
        }"""

        val useCase = buildScanUseCase()
        useCase.run(buildContext(nativeMode = true, token = "test-token-123"))

        assertTrue(recordingLicenseHttp.getCalled, "licenseValidator should have made an HTTP call to validate the license")
    }

    @Test
    fun `license validation called when only license token present`() = runTest {
        recordingLicenseHttp.responseBody = """{
            "licenseId": "test",
            "licenseKey": "key",
            "expirationDate": "2099-01-01",
            "projectIdHash": "hash1",
            "organisationIdHash": "hash2",
            "licensePlan": "ULTIMATE_PLUS"
        }"""

        val useCase = buildScanUseCase()
        useCase.run(buildContext(nativeMode = true, token = null, licenseOnlyToken = "license-only-token"))

        assertTrue(
            recordingLicenseHttp.getCalled,
            "licenseValidator should have made an HTTP call when license-only token is present"
        )
    }

    @Test
    fun `license validation skipped when no token`() = runTest {
        val useCase = buildScanUseCase()
        useCase.run(buildContext(nativeMode = true, token = null))

        assertFalse(recordingLicenseHttp.getCalled, "licenseValidator should NOT have been called when no token is present")
    }

    @Test
    fun `report not published when license-only`() = runTest {
        val useCase = buildScanUseCase()

        // licenseOnlyToken set but token is null => hasToken=false, isLicenseOnly=true
        // The publish guard requires hasToken && !isLicenseOnly, so publish is skipped
        useCase.run(buildContext(nativeMode = true, licenseOnlyToken = "license-only-token"))

        assertFalse(recordingPublisher.called, "report should NOT be published for a license-only token")
    }

    @Test
    fun `preloaded yaml in context is used for effective config`() = runTest {
        val useCase = buildScanUseCase()
        val baseContext = buildContext(nativeMode = false)
        val context = baseContext.copy(
            yaml = QodanaYaml(
                script = YamlScript(name = "custom-script"),
            ),
        )

        val result = useCase.run(context)

        assertEquals(ExitCode.SUCCESS.code, result)
        val command = recordingContainerEngine.lastSpec?.cmd ?: emptyList()
        assertEquals("custom-script", scriptArg(command))
    }

    @Test
    fun `analysis enriches sarif with automation and vcs metadata`() = runTest {
        val gitClient = RecordingGitClient(currentRevision = "abc123")
        val useCase = buildScanUseCase(gitClient = gitClient)
        val context = buildContext(nativeMode = true).copy(
            ci = CiContext(
                remoteUrl = "https://example.com/repo.git",
                branch = "main",
                revision = "abc123",
                jobUrl = "https://ci.example/job/42",
            ),
            linter = "qodana-jvm",
        )

        val result = useCase.run(context)

        assertEquals(ExitCode.SUCCESS.code, result)
        val root = ObjectMapper().readTree(Files.readString(resultsDir.resolve("qodana.sarif.json")))
        val run = root.path("runs").path(0)
        assertTrue(run.path("properties").path("deviceId").asText().isNotBlank())
        assertTrue(run.path("automationDetails").path("guid").asText().isNotBlank())
        assertEquals(
            "https://ci.example/job/42",
            run.path("automationDetails").path("properties").path("jobUrl").asText()
        )
        assertEquals(
            "https://example.com/repo.git",
            run.path("versionControlProvenance").path(0).path("repositoryUri").asText()
        )
        assertEquals("main", run.path("versionControlProvenance").path(0).path("branch").asText())
        assertEquals("abc123", run.path("versionControlProvenance").path(0).path("revisionId").asText())
    }

    @Test
    fun `scoped scenario runs staged analysis with scoped scripts and stage properties`() = runTest {
        val gitClient = RecordingGitClient(
            diffOutput = "src/Main.kt\n",
            currentRevision = "endHash",
        )
        val useCase = buildScanUseCase(gitClient = gitClient)
        val baseContext = buildContext(nativeMode = false)
        val context = baseContext.copy(
            scenario = RunScenario.Scoped(targetBranch = "startHash"),
            runtime = baseContext.runtime.copy(diffStart = "startHash", diffEnd = "endHash"),
        )

        val result = useCase.run(context)

        assertEquals(ExitCode.SUCCESS.code, result)
        assertEquals(listOf("startHash", "endHash"), gitClient.checkoutRefs)
        assertEquals(2, recordingContainerEngine.createdSpecs.size)

        val firstCommand = recordingContainerEngine.createdSpecs[0].cmd
        val secondCommand = recordingContainerEngine.createdSpecs[1].cmd
        val firstScript = scriptArg(firstCommand)
        val secondScript = scriptArg(secondCommand)
        assertTrue(firstScript?.startsWith("scoped:") == true)
        assertEquals(firstScript, secondScript)
        assertTrue(firstCommand.contains("--property=-Dqodana.skip.result=true"))
        assertTrue(firstCommand.contains("--property=-Dqodana.skip.coverage.computation=true"))
        assertTrue(secondCommand.contains("--property=-Dqodana.skip.preamble=true"))
        assertTrue(secondCommand.contains("--property=-Didea.headless.enable.statistics=false"))
        assertTrue(secondCommand.contains("--property=-Dqodana.skip.coverage.issues.reporting=true"))
        assertTrue(secondCommand.any { it.startsWith("--property=-Dqodana.scoped.baseline.path=") })
    }

    @Test
    fun `reverse-scoped scenario with fixes runs three stages`() = runTest {
        val gitClient = RecordingGitClient(
            diffOutput = "src/Main.kt\n",
            currentRevision = "endHash",
        )
        recordingContainerEngine.onCreate = { spec, callIndex ->
            val resultsDir = Path.of(spec.mounts.first { it.containerPath == "/data/results" }.hostPath)
            // "true" means stage was skipped, so we continue to the next stage.
            writeShortSarif(resultsDir, skipped = callIndex <= 2)
        }
        val useCase = buildScanUseCase(gitClient = gitClient)
        val baseContext = buildContext(nativeMode = false)
        val context = baseContext.copy(
            scenario = RunScenario.ReverseScoped(targetBranch = "startHash"),
            runtime = baseContext.runtime.copy(
                diffStart = "startHash",
                diffEnd = "endHash",
                applyFixes = true,
            ),
        )

        val result = useCase.run(context)

        assertEquals(ExitCode.SUCCESS.code, result)
        assertEquals(listOf("endHash", "startHash", "endHash"), gitClient.checkoutRefs)
        assertEquals(3, recordingContainerEngine.createdSpecs.size)

        val firstCommand = recordingContainerEngine.createdSpecs[0].cmd
        val secondCommand = recordingContainerEngine.createdSpecs[1].cmd
        val thirdCommand = recordingContainerEngine.createdSpecs[2].cmd
        val firstScript = scriptArg(firstCommand)
        val secondScript = scriptArg(secondCommand)
        val thirdScript = scriptArg(thirdCommand)
        assertTrue(firstScript?.startsWith("reverse-scoped:NEW,") == true)
        assertTrue(secondScript?.startsWith("reverse-scoped:OLD,") == true)
        assertTrue(thirdScript?.startsWith("reverse-scoped:FIXES,") == true)
        assertTrue(secondScript?.endsWith("reduced-scope.json") == true)
        assertTrue(thirdScript?.endsWith("reduced-scope.json") == true)

        assertTrue(firstCommand.any { it.startsWith("--property=-Dqodana.reduced.scope.path=") })
        assertTrue(secondCommand.contains("--property=-Dqodana.skip.result.strategy=FIXABLE"))
        assertTrue(secondCommand.any { it.startsWith("--property=-Dqodana.scoped.baseline.path=") })
        assertTrue(thirdCommand.contains("--property=-Dqodana.skip.result.strategy=NEVER"))
        assertTrue(thirdCommand.any { it.startsWith("--property=-Dqodana.scoped.baseline.path=") })
    }

    @Test
    fun `reverse-scoped stops after new stage when result is not skipped`() = runTest {
        val gitClient = RecordingGitClient(
            diffOutput = "src/Main.kt\n",
            currentRevision = "endHash",
        )
        recordingContainerEngine.onCreate = { spec, _ ->
            val resultsDir = Path.of(spec.mounts.first { it.containerPath == "/data/results" }.hostPath)
            writeShortSarif(resultsDir, skipped = false)
        }
        val useCase = buildScanUseCase(gitClient = gitClient)
        val baseContext = buildContext(nativeMode = false)
        val context = baseContext.copy(
            scenario = RunScenario.ReverseScoped(targetBranch = "startHash"),
            runtime = baseContext.runtime.copy(diffStart = "startHash", diffEnd = "endHash"),
        )

        val result = useCase.run(context)

        assertEquals(ExitCode.SUCCESS.code, result)
        assertEquals(listOf("endHash"), gitClient.checkoutRefs)
        assertEquals(1, recordingContainerEngine.createdSpecs.size)
    }

    private fun scriptArg(command: List<String>): String? {
        val scriptFlagIndex = command.indexOf("--script")
        return if (scriptFlagIndex >= 0 && scriptFlagIndex + 1 < command.size) {
            command[scriptFlagIndex + 1]
        } else {
            null
        }
    }

    private fun writeShortSarif(resultsDir: Path, skipped: Boolean) {
        Files.createDirectories(resultsDir)
        Files.writeString(
            resultsDir.resolve("qodana-short.sarif.json"),
            """{"runs":[{"invocations":[{"properties":{"qodana.result.skipped":$skipped}}]}]}""",
        )
    }

    // ----------------------------------------------------------------
    // Fakes & Recording Doubles
    // ----------------------------------------------------------------

    private class RecordingProcessRunner : ProcessRunner {
        var started = false
        var lastSpec: ProcessSpec? = null

        override suspend fun run(spec: ProcessSpec): ProcessResult {
            started = true
            lastSpec = spec
            return ProcessResult(exitCode = 0, stdout = "", stderr = "")
        }

        override suspend fun start(spec: ProcessSpec): RunningProcess {
            started = true
            lastSpec = spec
            return object : RunningProcess {
                override fun events(): Flow<LogEvent> = emptyFlow()
                override suspend fun awaitExit(): Int = 0
                override fun terminate() {}
            }
        }
    }

    private class RecordingContainerEngine : ContainerEngine {
        var created = false
        var lastSpec: ContainerRunSpec? = null
        val createdSpecs = mutableListOf<ContainerRunSpec>()
        var onCreate: ((ContainerRunSpec, Int) -> Unit)? = null

        override suspend fun pull(image: String, onProgress: (String) -> Unit) {}
        override suspend fun create(spec: ContainerRunSpec): String {
            created = true
            lastSpec = spec
            createdSpecs += spec
            onCreate?.invoke(spec, createdSpecs.size)
            return "fake-container-id"
        }

        override suspend fun start(containerId: String) {}
        override fun logs(containerId: String): Flow<LogEvent> = emptyFlow()
        override suspend fun wait(containerId: String) = ContainerExitStatus(exitCode = 0, oomKilled = false)
        override suspend fun remove(containerId: String, force: Boolean) {}
        override suspend fun info() = ContainerEngineInfo(engineType = EngineType.DOCKER, version = "24.0", memoryBytes = null)
        override suspend fun imageExists(image: String) = true
    }

    private class RecordingHttpTransport(var responseBody: String = "{}") : HttpTransport {
        var getCalled = false
        var lastUrl: String? = null

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            getCalled = true
            lastUrl = url
            return HttpResponse(200, responseBody)
        }

        override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>) =
            HttpResponse(200, "{}")

        override suspend fun download(url: String, target: Path, headers: Map<String, String>) {}

        override suspend fun uploadMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>) =
            HttpResponse(200, "{}")
    }

    private class RecordingReportPublisher : ReportPublisher {
        var called = false

        override suspend fun publish(analysisId: String, reportPath: Path, token: String, endpoint: String): PublishResult {
            called = true
            return PublishResult(url = "https://example.com/report", reportId = "test-id", success = true)
        }
    }

    private class RecordingGitClient(
        private val diffOutput: String = "src/Main.kt\n",
        private val currentRevision: String = "HEAD",
    ) : GitClient {
        val checkoutRefs = mutableListOf<String>()
        val revParseRefs = mutableListOf<String>()

        override suspend fun revParse(workDir: Path, ref: String): Result<String> {
            revParseRefs += ref
            return Result.success(ref)
        }

        override suspend fun checkout(workDir: Path, ref: String): Result<Unit> {
            checkoutRefs += ref
            return Result.success(Unit)
        }

        override suspend fun diff(workDir: Path, startRef: String?, endRef: String?): Result<String> {
            return Result.success(diffOutput)
        }

        override suspend fun log(workDir: Path, format: String, maxCount: Int?, allBranches: Boolean): Result<String> {
            return Result.success("")
        }

        override suspend fun branch(workDir: Path): Result<String> = Result.success("main")
        override suspend fun remoteUrl(workDir: Path): Result<String> = Result.success("https://example.com/repo.git")
        override suspend fun reset(workDir: Path, ref: String, hard: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun fetch(workDir: Path, remote: String?, ref: String?, depth: Int?): Result<Unit> = Result.success(Unit)
        override suspend fun isGitRepo(workDir: Path): Boolean = true
        override suspend fun currentBranch(workDir: Path): Result<String> = Result.success("main")
        override suspend fun currentRevision(workDir: Path): Result<String> = Result.success(currentRevision)
        override suspend fun clean(workDir: Path, force: Boolean, directories: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun submoduleUpdate(workDir: Path, init: Boolean, recursive: Boolean): Result<Unit> = Result.success(Unit)
    }

    /**
     * A [FileSystem] stub that reports all paths as existing (suitable for PrepareHost).
     * Can be configured with specific existing paths to match for NativeScan script resolution.
     */
    private class StubFileSystem(
        private val existingPaths: Set<String> = emptySet(),
        private val fileContents: Map<String, String> = emptyMap(),
    ) : FileSystem {
        override fun read(path: Path): String {
            val pathStr = path.toString()
            return fileContents.entries.find { pathStr.endsWith(it.key) }?.value ?: ""
        }
        override fun readBytes(path: Path) = byteArrayOf()
        override fun write(path: Path, content: String) {}
        override fun writeBytes(path: Path, content: ByteArray) {}
        override fun copy(source: Path, target: Path) {}
        override fun walk(root: Path, glob: String?) = emptySequence<Path>()
        override fun exists(path: Path): Boolean {
            if (existingPaths.isEmpty()) return true
            return existingPaths.any { path.toString().endsWith(it) }
        }

        override fun createDirectories(path: Path): Path = path
        override fun tempDir(prefix: String): Path = Path.of("/tmp/$prefix")
        override fun delete(path: Path) {}
        override fun extractArchive(archive: Path, target: Path) {}
    }


    private class ScanFakeTerminal : Terminal {
        override fun print(message: String) {}
        override fun println(message: String) {}
        override fun error(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun debug(message: String) {}
        override fun <T> spinner(message: String, action: () -> T): T = action()
        override fun prompt(message: String, default: String?): String = default ?: ""
        override fun select(message: String, choices: List<String>): String = choices.first()
        override val isInteractive: Boolean = false
        override var isCi: Boolean = false
        override fun setRedactedTokens(tokens: Set<String>) {}
    }

    private class ScanFakeSarifService : SarifService {
        override fun read(path: Path): Any {
            return SarifUtil.readReport(path)
        }

        override fun write(path: Path, report: Any) {}
        override fun merge(reports: List<Path>, output: Path) {}
        override fun baselineCompare(report: Path, baseline: Path, includeAbsent: Boolean) =
            BaselineResult(newCount = 0, unchangedCount = 0, absentCount = 0)

        override fun normalizePaths(reportPath: Path, projectDir: Path) {}
    }

    private class ScanFakeReportConverter : ReportConverter {
        override fun convertToHtml(resultsDir: Path, outputDir: Path) {}
    }
}

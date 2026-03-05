package org.jetbrains.qodana.engine.scan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.engine.port.ContainerEngine
import org.jetbrains.qodana.engine.port.ContainerEngineInfo
import org.jetbrains.qodana.engine.port.EngineType
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContainerScanTest {

    @Test
    fun `scan pulls image, creates, starts, waits, removes`() = runTest {
        val ops = mutableListOf<String>()
        val engine = FakeContainerEngine(ops, exitCode = 0)
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        val exitCode = scan.run(testContext(image = "jetbrains/qodana-jvm:2025.3"))

        assertEquals(0, exitCode)
        assertEquals(listOf("pull", "create", "start", "wait", "remove"), ops)
    }

    @Test
    fun `scan skips pull when skipPull is true`() = runTest {
        val ops = mutableListOf<String>()
        val engine = FakeContainerEngine(ops, exitCode = 0)
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        val exitCode = scan.run(testContext(image = "test:latest", skipPull = true))

        assertEquals(0, exitCode)
        assertTrue("pull" !in ops, "Should not pull when skipPull=true")
        assertTrue("create" in ops)
    }

    @Test
    fun `scan returns non-zero exit code`() = runTest {
        val ops = mutableListOf<String>()
        val engine = FakeContainerEngine(ops, exitCode = 255)
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        val exitCode = scan.run(testContext(image = "test:latest"))

        assertEquals(255, exitCode)
    }

    @Test
    fun `scan reports OOM`() = runTest {
        val ops = mutableListOf<String>()
        val engine = FakeContainerEngine(ops, exitCode = 137, oomKilled = true)
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        scan.run(testContext(image = "test:latest"))

        assertTrue(terminal.messages.any { it.contains("out-of-memory") || it.contains("OOM") })
    }

    @Test
    fun `scan mounts project, results, report, cache dirs`() = runTest {
        val engine = CapturingContainerEngine()
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        scan.run(testContext(image = "test:latest"))

        val spec = engine.capturedSpec!!
        val containerPaths = spec.mounts.map { it.containerPath }
        assertTrue("/data/project" in containerPaths, "Should mount project dir")
        assertTrue("/data/results" in containerPaths, "Should mount results dir")
        assertTrue("/data/report" in containerPaths, "Should mount report dir")
        assertTrue("/data/cache" in containerPaths, "Should mount cache dir")
    }

    @Test
    fun `scan passes QODANA_TOKEN as env var`() = runTest {
        val engine = CapturingContainerEngine()
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        scan.run(testContext(image = "test:latest", token = "my-token"))

        val spec = engine.capturedSpec!!
        assertEquals("my-token", spec.env["QODANA_TOKEN"])
    }

    @Test
    fun `dotnet image gets SYS_PTRACE capability`() = runTest {
        val engine = CapturingContainerEngine()
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        scan.run(testContext(image = "jetbrains/qodana-dotnet:2025.3"))

        val spec = engine.capturedSpec!!
        assertTrue("SYS_PTRACE" in spec.capAdd, "Dotnet should get SYS_PTRACE")
        assertTrue("seccomp=unconfined" in spec.securityOpts)
    }

    @Test
    fun `non-dotnet image does not get SYS_PTRACE`() = runTest {
        val engine = CapturingContainerEngine()
        val terminal = FakeTerminal()

        val scan = ContainerScan(engine, terminal)
        scan.run(testContext(image = "jetbrains/qodana-jvm:2025.3"))

        val spec = engine.capturedSpec!!
        assertTrue(spec.capAdd.isEmpty())
        assertTrue(spec.securityOpts.isEmpty())
    }

    @Test
    fun `scan passes additional volumes`() = runTest {
        val engine = CapturingContainerEngine()
        val terminal = FakeTerminal()

        val ctx = testContext(image = "test:latest").copy(
            docker = DockerOptions(
                image = "test:latest",
                volumes = listOf("/host/path:/container/path", "/data:/data:ro"),
            ),
        )
        val scan = ContainerScan(engine, terminal)
        scan.run(ctx)

        val spec = engine.capturedSpec!!
        val userMounts = spec.mounts.filter { it.containerPath == "/container/path" || it.containerPath == "/data" }
        assertEquals(2, userMounts.size)
    }

    private fun testContext(
        image: String,
        skipPull: Boolean = false,
        token: String? = null,
    ) = ScanContext(
        paths = ScanPaths(
            projectDir = Path.of("/tmp/project"),
            resultsDir = Path.of("/tmp/results"),
            cacheDir = Path.of("/tmp/cache"),
            reportDir = Path.of("/tmp/report"),
        ),
        auth = AuthContext(token = token, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(),
        ci = CiContext(),
        report = ReportOptions(),
        docker = DockerOptions(image = image, skipPull = skipPull),
    )
}

private class FakeContainerEngine(
    private val ops: MutableList<String>,
    private val exitCode: Int = 0,
    private val oomKilled: Boolean = false,
) : ContainerEngine {
    override suspend fun pull(image: String, onProgress: (String) -> Unit) { ops.add("pull") }
    override suspend fun create(spec: ContainerRunSpec): String { ops.add("create"); return "fake-id" }
    override suspend fun start(containerId: String) { ops.add("start") }
    override fun logs(containerId: String): Flow<LogEvent> = emptyFlow()
    override suspend fun wait(containerId: String): ContainerExitStatus {
        ops.add("wait")
        return ContainerExitStatus(exitCode = exitCode, oomKilled = oomKilled)
    }
    override suspend fun remove(containerId: String, force: Boolean) { ops.add("remove") }
    override suspend fun info() = ContainerEngineInfo(EngineType.DOCKER, "test", null)
    override suspend fun imageExists(image: String) = true
}

private class CapturingContainerEngine : ContainerEngine {
    var capturedSpec: ContainerRunSpec? = null

    override suspend fun pull(image: String, onProgress: (String) -> Unit) {}
    override suspend fun create(spec: ContainerRunSpec): String {
        capturedSpec = spec
        return "fake-id"
    }
    override suspend fun start(containerId: String) {}
    override fun logs(containerId: String): Flow<LogEvent> = emptyFlow()
    override suspend fun wait(containerId: String) = ContainerExitStatus(exitCode = 0)
    override suspend fun remove(containerId: String, force: Boolean) {}
    override suspend fun info() = ContainerEngineInfo(EngineType.DOCKER, "test", null)
    override suspend fun imageExists(image: String) = true
}

private class FakeTerminal : Terminal {
    val messages = mutableListOf<String>()
    override val isInteractive = false
    override var isCi = true
    override fun print(message: String) { messages.add(message) }
    override fun println(message: String) { messages.add(message) }
    override fun error(message: String) { messages.add("ERROR: $message") }
    override fun info(message: String) { messages.add(message) }
    override fun warn(message: String) { messages.add(message) }
    override fun debug(message: String) {}
    override fun <T> spinner(message: String, action: () -> T): T = action()
    override fun prompt(message: String, default: String?): String = default ?: ""
    override fun select(message: String, choices: List<String>): String = choices.first()
    override fun setRedactedTokens(tokens: Set<String>) {}
}

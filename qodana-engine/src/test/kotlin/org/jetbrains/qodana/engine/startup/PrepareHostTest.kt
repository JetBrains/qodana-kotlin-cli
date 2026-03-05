package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrepareHostTest {

    @Test
    fun `prepare creates required directories`() {
        val fs = FakeFileSystem()
        val terminal = FakeTerminal()
        val host = PrepareHost(fs, terminal)

        val ctx = testContext()
        host.prepare(ctx)

        assertTrue(fs.createdDirs.contains(ctx.paths.resultsDir))
        assertTrue(fs.createdDirs.contains(ctx.paths.reportDir))
        assertTrue(fs.createdDirs.contains(ctx.paths.cacheDir))
    }

    @Test
    fun `prepare clears cache when requested`() {
        val fs = FakeFileSystem()
        val terminal = FakeTerminal()
        val host = PrepareHost(fs, terminal)

        val ctx = testContext().copy(
            runtime = RuntimeContext(clearCache = true),
        )
        host.prepare(ctx)

        assertTrue(fs.deletedPaths.contains(ctx.paths.cacheDir))
    }

    @Test
    fun `prepare does not clear cache by default`() {
        val fs = FakeFileSystem()
        val terminal = FakeTerminal()
        val host = PrepareHost(fs, terminal)

        host.prepare(testContext())

        assertTrue(fs.deletedPaths.isEmpty())
    }

    @Test
    fun `prepare returns ideDir from context`() {
        val fs = FakeFileSystem()
        val terminal = FakeTerminal()
        val host = PrepareHost(fs, terminal)

        val ctx = testContext().copy(
            runtime = RuntimeContext(ideDir = Path.of("/opt/ide")),
        )
        val result = host.prepare(ctx)

        assertEquals(Path.of("/opt/ide"), result.ideDir)
    }

    @Test
    fun `prepare returns null ideDir when not set`() {
        val fs = FakeFileSystem()
        val terminal = FakeTerminal()
        val host = PrepareHost(fs, terminal)

        val result = host.prepare(testContext())

        assertNull(result.ideDir)
    }

    @Test
    fun `prepare returns upload token`() {
        val fs = FakeFileSystem()
        val terminal = FakeTerminal()
        val host = PrepareHost(fs, terminal)

        val ctx = testContext().copy(
            auth = AuthContext(token = "test-token", endpoint = "https://qodana.cloud"),
        )
        val result = host.prepare(ctx)

        assertEquals("test-token", result.uploadToken)
    }

    private fun testContext() = ScanContext(
        paths = ScanPaths(
            projectDir = Path.of("/project"),
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        ),
        auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(),
        ci = CiContext(),
        report = ReportOptions(),
        docker = DockerOptions(),
    )
}

private class FakeFileSystem : FileSystem {
    val createdDirs = mutableListOf<Path>()
    val deletedPaths = mutableListOf<Path>()

    override fun read(path: Path) = ""
    override fun readBytes(path: Path) = byteArrayOf()
    override fun write(path: Path, content: String) {}
    override fun writeBytes(path: Path, content: ByteArray) {}
    override fun copy(source: Path, target: Path) {}
    override fun walk(root: Path, glob: String?) = emptySequence<Path>()
    override fun exists(path: Path) = false
    override fun createDirectories(path: Path): Path { createdDirs.add(path); return path }
    override fun tempDir(prefix: String) = Path.of("/tmp/$prefix")
    override fun delete(path: Path) { deletedPaths.add(path) }
    override fun extractArchive(archive: Path, target: Path) {}
}

private class FakeTerminal : Terminal {
    override val isInteractive = false
    override var isCi = true
    override fun print(message: String) {}
    override fun println(message: String) {}
    override fun error(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
    override fun debug(message: String) {}
    override fun <T> spinner(message: String, action: () -> T): T = action()
    override fun prompt(message: String, default: String?): String = default ?: ""
    override fun select(message: String, choices: List<String>): String = choices.first()
    override fun setRedactedTokens(tokens: Set<String>) {}
}

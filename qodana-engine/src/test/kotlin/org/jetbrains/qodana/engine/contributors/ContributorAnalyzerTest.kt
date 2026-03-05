package org.jetbrains.qodana.engine.contributors

import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.engine.port.GitClient
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContributorAnalyzerTest {

    @Test
    fun `analyze groups commits by author`() = runTest {
        val git = FakeGitClient(
            logOutput = """
                alice@example.com||Alice||abc123||2026-03-01 10:00:00 +0000
                alice@example.com||Alice||def456||2026-03-02 10:00:00 +0000
                bob@example.com||Bob||ghi789||2026-03-01 10:00:00 +0000
            """.trimIndent(),
            remoteUrl = "https://github.com/user/project.git",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365)

        assertEquals(2, report.total)
        val alice = report.contributors.find { it.author.email == "alice@example.com" }!!
        assertEquals(2, alice.count)
        assertEquals("Alice", alice.author.username)
    }

    @Test
    fun `analyze filters bots by email`() = runTest {
        val git = FakeGitClient(
            logOutput = """
                alice@example.com||Alice||abc123||2026-03-01 10:00:00 +0000
                noreply@github.com||GitHub||def456||2026-03-01 10:00:00 +0000
            """.trimIndent(),
            remoteUrl = "https://github.com/user/project.git",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365, excludeBots = true)

        assertEquals(1, report.total)
        assertEquals("alice@example.com", report.contributors.first().author.email)
    }

    @Test
    fun `analyze filters bots by username`() = runTest {
        val git = FakeGitClient(
            logOutput = """
                alice@example.com||Alice||abc||2026-03-01 10:00:00 +0000
                bot@example.com||dependabot[bot]||def||2026-03-01 10:00:00 +0000
                bot2@example.com||renovate-bot||ghi||2026-03-01 10:00:00 +0000
            """.trimIndent(),
            remoteUrl = "https://github.com/user/project.git",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365, excludeBots = true)

        assertEquals(1, report.total)
    }

    @Test
    fun `analyze includes bots when excludeBots is false`() = runTest {
        val git = FakeGitClient(
            logOutput = """
                noreply@github.com||GitHub||abc||2026-03-01 10:00:00 +0000
            """.trimIndent(),
            remoteUrl = "https://github.com/user/project.git",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365, excludeBots = false)

        assertEquals(1, report.total)
    }

    @Test
    fun `analyze extracts project name from remote URL`() = runTest {
        val git = FakeGitClient(
            logOutput = "alice@example.com||Alice||abc||2026-03-01 10:00:00 +0000",
            remoteUrl = "https://github.com/org/my-project.git",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365)

        assertTrue(report.contributors.first().projects.contains("my-project"))
    }

    @Test
    fun `analyze returns empty on git failure`() = runTest {
        val git = FakeGitClient(logOutput = null, remoteUrl = "url")
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365)

        assertEquals(0, report.total)
    }

    @Test
    fun `analyze sorts by commit count descending`() = runTest {
        val git = FakeGitClient(
            logOutput = """
                bob@x.com||Bob||a||2026-03-01 10:00:00 +0000
                alice@x.com||Alice||b||2026-03-01 10:00:00 +0000
                alice@x.com||Alice||c||2026-03-02 10:00:00 +0000
                alice@x.com||Alice||d||2026-03-03 10:00:00 +0000
            """.trimIndent(),
            remoteUrl = "url",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365)

        assertEquals("alice@x.com", report.contributors.first().author.email)
        assertEquals(3, report.contributors.first().count)
    }

    @Test
    fun `analyze skips malformed lines`() = runTest {
        val git = FakeGitClient(
            logOutput = """
                alice@x.com||Alice||abc||2026-03-01 10:00:00 +0000
                bad-line-no-separators
                ||just-name||sha||2026-03-01 10:00:00 +0000
            """.trimIndent(),
            remoteUrl = "url",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365)

        assertEquals(2, report.total)
    }

    @Test
    fun `analyze filters noreply github emails`() = runTest {
        val git = FakeGitClient(
            logOutput = """
                12345+user@users.noreply.github.com||user||abc||2026-03-01 10:00:00 +0000
                real@example.com||Real||def||2026-03-01 10:00:00 +0000
            """.trimIndent(),
            remoteUrl = "url",
        )
        val analyzer = ContributorAnalyzer(git)

        val report = analyzer.analyze(listOf(Path.of("/repo")), days = 365, excludeBots = true)

        assertEquals(1, report.total)
        assertEquals("real@example.com", report.contributors.first().author.email)
    }
}

private class FakeGitClient(
    private val logOutput: String?,
    private val remoteUrl: String,
) : GitClient {
    override suspend fun currentRevision(workDir: Path) = Result.success("abc")
    override suspend fun currentBranch(workDir: Path) = Result.success("main")
    override suspend fun revParse(workDir: Path, ref: String) = Result.success("abc")
    override suspend fun checkout(workDir: Path, ref: String) = Result.success(Unit)
    override suspend fun diff(workDir: Path, startRef: String?, endRef: String?) = Result.success("")
    override suspend fun log(workDir: Path, format: String, maxCount: Int?): Result<String> {
        return if (logOutput != null) Result.success(logOutput)
        else Result.failure(RuntimeException("git not available"))
    }
    override suspend fun remoteUrl(workDir: Path) = Result.success(remoteUrl)
    override suspend fun reset(workDir: Path, ref: String, hard: Boolean) = Result.success(Unit)
    override suspend fun fetch(workDir: Path, remote: String?, ref: String?, depth: Int?) = Result.success(Unit)
    override suspend fun isGitRepo(workDir: Path) = true
    override suspend fun branch(workDir: Path) = Result.success("main")
    override suspend fun clean(workDir: Path, force: Boolean, directories: Boolean) = Result.success(Unit)
    override suspend fun submoduleUpdate(workDir: Path, init: Boolean, recursive: Boolean) = Result.success(Unit)
}

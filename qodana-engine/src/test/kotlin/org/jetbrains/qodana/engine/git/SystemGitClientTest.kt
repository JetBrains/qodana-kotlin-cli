package org.jetbrains.qodana.engine.git

import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.core.process.SystemProcessRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemGitClientTest {

    companion object {
        private lateinit var git: SystemGitClient

        @JvmStatic
        @BeforeAll
        fun setup() {
            val runner = SystemProcessRunner()
            git = SystemGitClient(runner)
            // Check git is available
            try {
                val result = kotlinx.coroutines.runBlocking {
                    runner.run(org.jetbrains.qodana.core.model.ProcessSpec(command = "git", args = listOf("--version")))
                }
                assumeTrue(result.exitCode == 0, "Git is not available")
            } catch (e: Exception) {
                assumeTrue(false, "Git is not available: ${e.message}")
            }
        }
    }

    private fun initRepo(dir: Path) {
        ProcessBuilder("git", "init").directory(dir.toFile()).start().waitFor()
        ProcessBuilder("git", "config", "user.email", "test@test.com").directory(dir.toFile()).start().waitFor()
        ProcessBuilder("git", "config", "user.name", "Test User").directory(dir.toFile()).start().waitFor()
    }

    private fun commitFile(dir: Path, name: String, content: String) {
        Files.writeString(dir.resolve(name), content)
        ProcessBuilder("git", "add", name).directory(dir.toFile()).start().waitFor()
        ProcessBuilder("git", "commit", "-m", "add $name").directory(dir.toFile()).start().waitFor()
    }

    @Test
    fun `isGitRepo returns true for git repo`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        assertTrue(git.isGitRepo(dir))
    }

    @Test
    fun `isGitRepo returns false for non-repo`(@TempDir dir: Path) = runTest {
        assertFalse(git.isGitRepo(dir))
    }

    @Test
    fun `currentRevision returns commit hash`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "hello")

        val result = git.currentRevision(dir)
        assertTrue(result.isSuccess)
        val hash = result.getOrThrow()
        assertTrue(hash.length >= 7, "Expected SHA hash, got: $hash")
        assertTrue(hash.matches(Regex("[0-9a-f]+")), "Expected hex hash, got: $hash")
    }

    @Test
    fun `currentBranch returns branch name`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "hello")

        val result = git.currentBranch(dir)
        assertTrue(result.isSuccess)
        val branch = result.getOrThrow()
        assertTrue(branch.isNotBlank(), "Branch should not be blank")
    }

    @Test
    fun `revParse HEAD`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "hello")

        val result = git.revParse(dir, "HEAD")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `revParse invalid ref fails`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "hello")

        val result = git.revParse(dir, "nonexistent-ref-xyz")
        assertTrue(result.isFailure)
    }

    @Test
    fun `checkout branch`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "hello")
        ProcessBuilder("git", "checkout", "-b", "feature").directory(dir.toFile()).start().waitFor()
        commitFile(dir, "feature.txt", "feature content")

        // Checkout back to initial branch
        val mainBranch = git.currentBranch(dir).getOrThrow()
        // We're on feature, checkout back
        ProcessBuilder("git", "checkout", "-").directory(dir.toFile()).start().waitFor()
        val result = git.checkout(dir, "feature")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `diff between commits`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "version1")
        val first = git.revParse(dir, "HEAD").getOrThrow()
        commitFile(dir, "test.txt", "version2")

        val result = git.diff(dir, first, "HEAD")
        assertTrue(result.isSuccess)
        val diff = result.getOrThrow()
        assertTrue(diff.contains("version1") || diff.contains("version2") || diff.contains("test.txt"),
            "Diff should reference changed content or file")
    }

    @Test
    fun `log returns commit messages`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "a.txt", "a")
        commitFile(dir, "b.txt", "b")

        val result = git.log(dir, "%H %s", maxCount = 2)
        assertTrue(result.isSuccess)
        val log = result.getOrThrow()
        assertTrue(log.contains("add a"))
        assertTrue(log.contains("add b"))
    }

    @Test
    fun `remoteUrl on repo without remote`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "hello")

        val result = git.remoteUrl(dir)
        // Should fail or return empty — no remote configured
        assertTrue(result.isFailure || result.getOrThrow().isBlank())
    }

    @Test
    fun `reset hard`(@TempDir dir: Path) = runTest {
        initRepo(dir)
        commitFile(dir, "test.txt", "original")
        val originalHash = git.revParse(dir, "HEAD").getOrThrow()
        commitFile(dir, "test.txt", "modified")

        val result = git.reset(dir, originalHash, hard = true)
        assertTrue(result.isSuccess)

        val content = Files.readString(dir.resolve("test.txt"))
        assertEquals("original", content)
    }
}

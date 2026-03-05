package org.jetbrains.qodana.engine.report

import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.engine.port.GitClient
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class SarifVersioningTest {

    private fun fakeGit(
        remoteUrl: String = "https://github.com/user/repo.git",
        branch: String = "main",
        revision: String = "abc123",
        authorName: String = "Test Author",
        authorEmail: String = "test@example.com",
    ) = object : GitClient {
        override suspend fun revParse(workDir: Path, ref: String) = Result.success(revision)
        override suspend fun checkout(workDir: Path, ref: String) = Result.success(Unit)
        override suspend fun diff(workDir: Path, startRef: String?, endRef: String?) = Result.success("")
        override suspend fun log(workDir: Path, format: String, maxCount: Int?): Result<String> {
            return when (format) {
                "%an" -> Result.success(authorName)
                "%ae" -> Result.success(authorEmail)
                else -> Result.success("")
            }
        }
        override suspend fun branch(workDir: Path) = Result.success(branch)
        override suspend fun remoteUrl(workDir: Path) = Result.success(remoteUrl)
        override suspend fun reset(workDir: Path, ref: String, hard: Boolean) = Result.success(Unit)
        override suspend fun fetch(workDir: Path, remote: String?, ref: String?, depth: Int?) = Result.success(Unit)
        override suspend fun isGitRepo(workDir: Path) = true
        override suspend fun currentBranch(workDir: Path) = Result.success(branch)
        override suspend fun currentRevision(workDir: Path) = Result.success(revision)
        override suspend fun clean(workDir: Path, force: Boolean, directories: Boolean) = Result.success(Unit)
        override suspend fun submoduleUpdate(workDir: Path, init: Boolean, recursive: Boolean) = Result.success(Unit)
    }

    @Test
    fun `getVersionDetails from git`() = runTest {
        val versioning = SarifVersioning(fakeGit())
        val details = versioning.getVersionDetails(
            Path.of("/project"),
            envRemoteUrl = null,
            envBranch = null,
            envRevision = null,
        )

        assertEquals("https://github.com/user/repo.git", details.repositoryUri)
        assertEquals("main", details.branch)
        assertEquals("abc123", details.revisionId)
        assertEquals("Test Author", details.lastAuthorName)
        assertEquals("test@example.com", details.lastAuthorEmail)
    }

    @Test
    fun `env vars override git values`() = runTest {
        val versioning = SarifVersioning(fakeGit())
        val details = versioning.getVersionDetails(
            Path.of("/project"),
            envRemoteUrl = "https://custom.url/repo",
            envBranch = "custom-branch",
            envRevision = "custom-rev",
        )

        assertEquals("https://custom.url/repo", details.repositoryUri)
        assertEquals("custom-branch", details.branch)
        assertEquals("custom-rev", details.revisionId)
    }

    @Test
    fun `no git client returns empty values`() = runTest {
        val versioning = SarifVersioning(null)
        val details = versioning.getVersionDetails(
            Path.of("/project"),
            envRemoteUrl = null,
            envBranch = null,
            envRevision = null,
        )

        assertEquals("", details.repositoryUri)
        assertEquals("", details.branch)
        assertEquals("", details.revisionId)
        assertEquals("", details.lastAuthorName)
        assertEquals("", details.lastAuthorEmail)
    }

    @Test
    fun `ssh url without scheme gets ssh prefix`() = runTest {
        val versioning = SarifVersioning(fakeGit(remoteUrl = "git@github.com:user/repo.git"))
        val details = versioning.getVersionDetails(
            Path.of("/project"),
            envRemoteUrl = null,
            envBranch = null,
            envRevision = null,
        )

        assertEquals("ssh://git@github.com:user/repo.git", details.repositoryUri)
    }

    @Test
    fun `detached HEAD returns empty branch`() = runTest {
        val versioning = SarifVersioning(fakeGit(branch = "HEAD"))
        val details = versioning.getVersionDetails(
            Path.of("/project"),
            envRemoteUrl = null,
            envBranch = null,
            envRevision = null,
        )

        assertEquals("", details.branch)
    }

    @Test
    fun `git failure returns empty values`() = runTest {
        val failingGit = object : GitClient {
            override suspend fun revParse(workDir: Path, ref: String) = Result.failure<String>(Exception("fail"))
            override suspend fun checkout(workDir: Path, ref: String) = Result.failure<Unit>(Exception("fail"))
            override suspend fun diff(workDir: Path, startRef: String?, endRef: String?) = Result.failure<String>(Exception("fail"))
            override suspend fun log(workDir: Path, format: String, maxCount: Int?) = Result.failure<String>(Exception("fail"))
            override suspend fun branch(workDir: Path) = Result.failure<String>(Exception("fail"))
            override suspend fun remoteUrl(workDir: Path) = Result.failure<String>(Exception("fail"))
            override suspend fun reset(workDir: Path, ref: String, hard: Boolean) = Result.failure<Unit>(Exception("fail"))
            override suspend fun fetch(workDir: Path, remote: String?, ref: String?, depth: Int?) = Result.failure<Unit>(Exception("fail"))
            override suspend fun isGitRepo(workDir: Path) = false
            override suspend fun currentBranch(workDir: Path) = Result.failure<String>(Exception("fail"))
            override suspend fun currentRevision(workDir: Path) = Result.failure<String>(Exception("fail"))
            override suspend fun clean(workDir: Path, force: Boolean, directories: Boolean) = Result.failure<Unit>(Exception("fail"))
            override suspend fun submoduleUpdate(workDir: Path, init: Boolean, recursive: Boolean) = Result.failure<Unit>(Exception("fail"))
        }

        val versioning = SarifVersioning(failingGit)
        val details = versioning.getVersionDetails(
            Path.of("/project"),
            envRemoteUrl = null,
            envBranch = null,
            envRevision = null,
        )

        assertEquals("", details.repositoryUri)
        assertEquals("", details.branch)
        assertEquals("", details.revisionId)
        assertEquals("", details.lastAuthorName)
        assertEquals("", details.lastAuthorEmail)
    }
}

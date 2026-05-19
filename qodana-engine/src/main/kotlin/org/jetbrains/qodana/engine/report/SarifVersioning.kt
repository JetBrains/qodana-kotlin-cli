package org.jetbrains.qodana.engine.report

import org.jetbrains.qodana.engine.port.GitClient
import java.nio.file.Path

data class VersionControlDetails(
    val repositoryUri: String = "",
    val branch: String = "",
    val revisionId: String = "",
    val lastAuthorName: String = "",
    val lastAuthorEmail: String = "",
)

class SarifVersioning(
    private val gitClient: GitClient?,
) {
    suspend fun getVersionDetails(
        projectDir: Path,
        envRemoteUrl: String? = System.getenv("QODANA_REMOTE_URL"),
        envBranch: String? = System.getenv("QODANA_BRANCH"),
        envRevision: String? = System.getenv("QODANA_REVISION"),
    ): VersionControlDetails {
        val git = gitClient

        val repositoryUri =
            when {
                !envRemoteUrl.isNullOrEmpty() -> envRemoteUrl
                git != null -> getRepositoryUri(git, projectDir)
                else -> ""
            }

        val branch =
            when {
                !envBranch.isNullOrEmpty() -> envBranch
                git != null -> getBranchName(git, projectDir)
                else -> ""
            }

        val revisionId =
            when {
                !envRevision.isNullOrEmpty() -> envRevision
                git != null -> getRevisionId(git, projectDir)
                else -> ""
            }

        val lastAuthorName = if (git != null) getLastAuthorName(git, projectDir) else ""
        val lastAuthorEmail = if (git != null) getAuthorEmail(git, projectDir) else ""

        return VersionControlDetails(
            repositoryUri = repositoryUri,
            branch = branch,
            revisionId = revisionId,
            lastAuthorName = lastAuthorName,
            lastAuthorEmail = lastAuthorEmail,
        )
    }

    private suspend fun getRepositoryUri(
        git: GitClient,
        projectDir: Path,
    ): String {
        val result = git.remoteUrl(projectDir)
        val url = result.getOrNull()?.trim() ?: return ""
        return if ("://" !in url) "ssh://$url" else url
    }

    private suspend fun getBranchName(
        git: GitClient,
        projectDir: Path,
    ): String {
        val result = git.currentBranch(projectDir)
        val branch = result.getOrNull()?.trim() ?: return ""
        return if (branch == "HEAD") "" else branch
    }

    private suspend fun getRevisionId(
        git: GitClient,
        projectDir: Path,
    ): String = git.currentRevision(projectDir).getOrNull()?.trim() ?: ""

    private suspend fun getLastAuthorName(
        git: GitClient,
        projectDir: Path,
    ): String = git.log(projectDir, "%an", maxCount = 1).getOrNull()?.trim() ?: ""

    private suspend fun getAuthorEmail(
        git: GitClient,
        projectDir: Path,
    ): String = git.log(projectDir, "%ae", maxCount = 1).getOrNull()?.trim() ?: ""
}

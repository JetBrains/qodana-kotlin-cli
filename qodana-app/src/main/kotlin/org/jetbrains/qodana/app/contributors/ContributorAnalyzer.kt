package org.jetbrains.qodana.app.contributors

import org.jetbrains.qodana.core.port.GitClient
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ContributorsReport(
    val total: Int,
    val contributors: List<Contributor>,
)

data class Contributor(
    val author: ContributorAuthor,
    val projects: List<String>,
    val count: Int,
    val commits: List<ContributorCommit>,
)

data class ContributorAuthor(
    val email: String,
    val username: String,
)

data class ContributorCommit(
    val date: String,
    val sha256: String,
)

class ContributorAnalyzer(private val gitClient: GitClient) {

    companion object {
        private const val LOG_FORMAT = "%aE||%aN||%H||%ai"
        private const val FIELD_SEPARATOR = "||"

        private val BOT_EMAIL_PATTERNS = listOf(
            "noreply@github.com",
            "qodana-support@jetbrains.com",
        )
        private val BOT_USERNAME_PATTERNS = listOf(
            "dependabot",
            "renovate",
        )
    }

    suspend fun analyze(
        repoDirs: List<Path>,
        days: Int,
        excludeBots: Boolean = true,
    ): ContributorsReport {
        val cutoffDate = LocalDate.now().minusDays(days.toLong())
        val allCommits = repoDirs.flatMap { repoDir ->
            collectCommits(repoDir, cutoffDate)
        }

        val filtered = if (excludeBots) allCommits.filter { !isBot(it) } else allCommits

        val grouped = filtered.groupBy { it.authorId }

        val contributors = grouped.map { (_, commits) ->
            val first = commits.first()
            Contributor(
                author = ContributorAuthor(
                    email = first.email,
                    username = first.username,
                ),
                projects = commits.map { it.project }.distinct().sorted(),
                count = commits.size,
                commits = commits.map { ContributorCommit(date = it.date, sha256 = it.sha) },
            )
        }.sortedByDescending { it.count }

        return ContributorsReport(
            total = contributors.size,
            contributors = contributors,
        )
    }

    private suspend fun collectCommits(repoDir: Path, cutoffDate: LocalDate): List<ParsedCommit> {
        val logOutput = gitClient.log(repoDir, LOG_FORMAT).getOrElse { return emptyList() }
        val project = resolveProjectName(repoDir)

        return logOutput.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, project) }
            .filter { commit ->
                try {
                    val commitDate = LocalDate.parse(
                        commit.date.substringBefore(" "),
                        DateTimeFormatter.ISO_LOCAL_DATE,
                    )
                    !commitDate.isBefore(cutoffDate)
                } catch (_: Exception) {
                    false
                }
            }
    }

    private suspend fun resolveProjectName(repoDir: Path): String {
        return gitClient.remoteUrl(repoDir)
            .map { url -> extractProjectFromUrl(url) }
            .getOrElse { repoDir.fileName?.toString() ?: repoDir.toString() }
    }

    private fun extractProjectFromUrl(url: String): String {
        return url.trimEnd('/')
            .removeSuffix(".git")
            .substringAfterLast('/')
    }

    private fun parseLine(line: String, project: String): ParsedCommit? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 4) return null

        val email = parts[0].trim()
        val username = parts[1].trim()
        val sha = parts[2].trim()
        val date = parts[3].trim()

        val authorId = email.ifBlank { username }
        if (authorId.isBlank()) return null

        return ParsedCommit(
            email = email,
            username = username,
            sha = sha,
            date = date,
            authorId = authorId,
            project = project,
        )
    }

    private fun isBot(commit: ParsedCommit): Boolean {
        val emailLower = commit.email.lowercase()
        val usernameLower = commit.username.lowercase()

        if (BOT_EMAIL_PATTERNS.any { emailLower.contains(it) }) return true
        if (emailLower.endsWith("@users.noreply.github.com")) return true
        if (BOT_USERNAME_PATTERNS.any { pattern -> usernameLower.contains(pattern) }) return true

        return false
    }

    private data class ParsedCommit(
        val email: String,
        val username: String,
        val sha: String,
        val date: String,
        val authorId: String,
        val project: String,
    )
}

package org.jetbrains.qodana.engine.contributors

import org.jetbrains.qodana.engine.port.GitClient
import java.nio.file.Files
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
        val cutoffDate = if (days > 0) LocalDate.now().minusDays(days.toLong()) else null
        val allCommits = repoDirs.flatMap { repoDir ->
            collectCommits(repoDir, cutoffDate)
        }

        val withoutQodanaBot = allCommits.filterNot { commit ->
            commit.email.equals("qodana-support@jetbrains.com", ignoreCase = true)
        }
        val filtered = if (excludeBots) withoutQodanaBot.filter { !isBot(it) } else withoutQodanaBot

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

    private suspend fun collectCommits(repoDir: Path, cutoffDate: LocalDate?): List<ParsedCommit> {
        val logOutput = gitClient.log(repoDir, LOG_FORMAT, allBranches = true).getOrElse { return emptyList() }
        val project = resolveProjectName(repoDir)
        val mailmap = loadMailmap(repoDir)

        return logOutput.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, project, mailmap) }
            .filter { commit ->
                if (cutoffDate == null) return@filter true
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

    private fun parseLine(
        line: String,
        project: String,
        mailmap: List<MailmapRule>,
    ): ParsedCommit? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 4) return null

        val email = parts[0].trim()
        val username = parts[1].trim()
        val sha = parts[2].trim()
        val date = parts[3].trim()

        val authorId = email.ifBlank { username }
        if (authorId.isBlank()) return null

        val parsed = ParsedCommit(
            email = email,
            username = username,
            sha = sha,
            date = date,
            authorId = authorId,
            project = project,
        )

        return applyMailmap(parsed, mailmap)
    }

    private fun loadMailmap(repoDir: Path): List<MailmapRule> {
        val mailmapPath = repoDir.resolve(".mailmap")
        if (!Files.exists(mailmapPath) || !Files.isRegularFile(mailmapPath)) {
            return emptyList()
        }
        return runCatching {
            Files.readAllLines(mailmapPath)
                .mapNotNull(::parseMailmapRule)
        }.getOrDefault(emptyList())
    }

    private fun parseMailmapRule(rawLine: String): MailmapRule? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return null

        val pairRegex = Regex("""([^<]*)<([^>]+)>""")
        val pairs = pairRegex.findAll(line)
            .map { match ->
                NameEmailPair(
                    name = match.groupValues[1].trim().ifBlank { null },
                    email = match.groupValues[2].trim().ifBlank { null },
                )
            }
            .toList()

        if (pairs.isEmpty()) return null

        val canonical = pairs[0]
        val old = if (pairs.size >= 2) pairs[1] else NameEmailPair(
            name = null,
            email = canonical.email,
        )

        if (old.email.isNullOrBlank()) return null

        return MailmapRule(
            canonicalName = canonical.name,
            canonicalEmail = canonical.email,
            oldName = old.name,
            oldEmail = old.email,
        )
    }

    private fun applyMailmap(commit: ParsedCommit, rules: List<MailmapRule>): ParsedCommit {
        if (rules.isEmpty()) return commit

        val exactRule = rules.firstOrNull {
            it.oldEmail.equals(commit.email, ignoreCase = true) &&
                !it.oldName.isNullOrBlank() &&
                it.oldName.equals(commit.username, ignoreCase = true)
        }
        val fallbackRule = rules.firstOrNull {
            it.oldEmail.equals(commit.email, ignoreCase = true) &&
                it.oldName.isNullOrBlank()
        }
        val rule = exactRule ?: fallbackRule ?: return commit

        val normalizedEmail = rule.canonicalEmail?.takeIf { it.isNotBlank() } ?: commit.email
        val normalizedName = rule.canonicalName?.takeIf { it.isNotBlank() } ?: commit.username
        val normalizedAuthorId = normalizedEmail.ifBlank { normalizedName }

        return commit.copy(
            email = normalizedEmail,
            username = normalizedName,
            authorId = normalizedAuthorId,
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

    private data class MailmapRule(
        val canonicalName: String?,
        val canonicalEmail: String?,
        val oldName: String?,
        val oldEmail: String?,
    )

    private data class NameEmailPair(
        val name: String?,
        val email: String?,
    )
}

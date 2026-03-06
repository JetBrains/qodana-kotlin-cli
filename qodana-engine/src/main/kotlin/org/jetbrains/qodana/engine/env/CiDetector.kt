package org.jetbrains.qodana.engine.env

import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.engine.model.CiContext

object CiDetector {
    fun detect(getEnv: (String) -> String? = System::getenv): CiContext? {
        return detectGitHub(getEnv)
            ?: detectGitLab(getEnv)
            ?: detectJenkins(getEnv)
            ?: detectAzure(getEnv)
            ?: detectBitBucket(getEnv)
            ?: detectSpace(getEnv)
            ?: detectTeamCity(getEnv)
            ?: detectCircleCI(getEnv)
    }

    /**
     * Extracts Qodana environment from CI context, applying overrides from
     * QODANA_REMOTE_URL, QODANA_BRANCH, QODANA_REVISION, QODANA_JOB_URL env vars.
     * Sets QODANA_ENV with the format "ciName:version".
     */
    fun extractQodanaEnvironment(
        ci: CiContext?,
        getEnv: (String) -> String? = System::getenv,
    ): CiContext {
        val base = ci ?: CiContext()
        val ciName = base.ciName ?: "cli"
        val branch = getEnv(QodanaEnv.BRANCH)
            ?: validateBranch(base.branch ?: "", ciName, getEnv)
        val revision = getEnv(QodanaEnv.REVISION) ?: base.revision
        val remoteUrl = getEnv(QodanaEnv.REMOTE_URL)
            ?: validateRemoteUrl(base.remoteUrl ?: "", ciName, getEnv)
        val jobUrl = getEnv(QodanaEnv.JOB_URL)
            ?: validateJobUrl(base.jobUrl ?: "", ciName, getEnv)

        return CiContext(
            ciName = getEnv(QodanaEnv.ENV) ?: ciName,
            branch = branch.ifEmpty { null },
            revision = revision,
            remoteUrl = remoteUrl.ifEmpty { null },
            jobUrl = jobUrl.ifEmpty { null },
        )
    }

    fun isContainer(getEnv: (String) -> String? = System::getenv): Boolean =
        !getEnv(QodanaEnv.DOCKER).isNullOrEmpty()

    fun isBitBucket(getEnv: (String) -> String? = System::getenv): Boolean =
        !getEnv("BITBUCKET_PIPELINE_UUID").isNullOrEmpty()

    fun isBitBucketPipe(getEnv: (String) -> String? = System::getenv): Boolean =
        !getEnv("BITBUCKET_PIPE_STORAGE_DIR").isNullOrEmpty() ||
            !getEnv("BITBUCKET_PIPE_SHARED_STORAGE_DIR").isNullOrEmpty()

    fun isGitLab(getEnv: (String) -> String? = System::getenv): Boolean =
        getEnv("GITLAB_CI") == "true"

    fun getBitBucketRepoFullName(getEnv: (String) -> String? = System::getenv): String =
        getEnv("BITBUCKET_REPO_FULL_NAME") ?: ""

    fun getBitBucketRepoOwner(getEnv: (String) -> String? = System::getenv): String =
        getBitBucketRepoFullName(getEnv).substringBefore("/", "")

    fun getBitBucketRepoName(getEnv: (String) -> String? = System::getenv): String =
        getBitBucketRepoFullName(getEnv).substringAfter("/", "")

    fun validateBranch(branch: String, ciName: String, getEnv: (String) -> String? = System::getenv): String {
        if (branch.isNotEmpty()) return branch
        return when (ciName) {
            "github-actions" -> getEnv("GITHUB_REF_NAME") ?: ""
            "azure-pipelines" -> getEnv("BUILD_SOURCEBRANCHNAME") ?: ""
            "jenkins" -> getEnv("GIT_BRANCH") ?: ""
            "gitlab-ci" -> getEnv("CI_COMMIT_REF_NAME") ?: ""
            "bitbucket" -> getEnv("BITBUCKET_BRANCH") ?: ""
            else -> ""
        }
    }

    fun validateRemoteUrl(remoteUrl: String, ciName: String, getEnv: (String) -> String? = System::getenv): String {
        if (ciName.startsWith("space")) {
            return getSpaceRemoteUrl(getEnv)
        }
        if (remoteUrl.isEmpty()) return ""
        return try {
            java.net.URI(remoteUrl)
            if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://") ||
                remoteUrl.startsWith("ssh://") || remoteUrl.startsWith("git@")
            ) remoteUrl else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun validateJobUrl(jobUrl: String, ciName: String, getEnv: (String) -> String? = System::getenv): String {
        if (ciName.startsWith("azure")) {
            return getAzureJobUrl(getEnv)
        }
        if (jobUrl.isEmpty()) return ""
        return try {
            val uri = java.net.URI(jobUrl)
            if (uri.scheme != null && uri.host != null) jobUrl else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun getSpaceRemoteUrl(getEnv: (String) -> String?): String {
        val server = getEnv("JB_SPACE_API_URL") ?: return ""
        val projectKey = getEnv("JB_SPACE_PROJECT_KEY") ?: ""
        val repoName = getEnv("JB_SPACE_GIT_REPOSITORY_NAME") ?: ""
        return "ssh://git@git.$server/$projectKey/$repoName.git"
    }

    private fun getAzureJobUrl(getEnv: (String) -> String?): String {
        val server = getEnv("SYSTEM_TEAMFOUNDATIONCOLLECTIONURI") ?: return ""
        val project = getEnv("SYSTEM_TEAMPROJECT") ?: ""
        val buildId = getEnv("BUILD_BUILDID") ?: ""
        return "${server}${project}/_build/results?buildId=${buildId}"
    }

    fun unsetRubyVariables() {
        System.clearProperty(QodanaEnv.GEM_HOME)
        System.clearProperty(QodanaEnv.BUNDLE_APP_CONFIG)
    }

    private fun detectGitHub(env: (String) -> String?): CiContext? {
        env("GITHUB_ACTIONS") ?: return null
        return CiContext(
            ciName = "github-actions",
            branch = env("GITHUB_REF_NAME"),
            revision = env("GITHUB_SHA"),
            remoteUrl = env("GITHUB_SERVER_URL")?.let { "$it/${env("GITHUB_REPOSITORY")}" },
            jobUrl = env("GITHUB_SERVER_URL")?.let { "$it/${env("GITHUB_REPOSITORY")}/actions/runs/${env("GITHUB_RUN_ID")}" },
        )
    }

    private fun detectGitLab(env: (String) -> String?): CiContext? {
        if (env("GITLAB_CI") != "true") return null
        return CiContext(
            ciName = "gitlab-ci",
            branch = env("CI_COMMIT_REF_NAME"),
            revision = env("CI_COMMIT_SHA"),
            remoteUrl = env("CI_REPOSITORY_URL"),
            jobUrl = env("CI_JOB_URL"),
        )
    }

    private fun detectJenkins(env: (String) -> String?): CiContext? {
        env("JENKINS_URL") ?: return null
        return CiContext(
            ciName = "jenkins",
            branch = env("GIT_BRANCH"),
            revision = env("GIT_COMMIT"),
            remoteUrl = env("GIT_URL"),
            jobUrl = env("BUILD_URL"),
        )
    }

    private fun detectAzure(env: (String) -> String?): CiContext? {
        env("SYSTEM_TEAMFOUNDATIONCOLLECTIONURI") ?: return null
        val server = env("SYSTEM_TEAMFOUNDATIONCOLLECTIONURI") ?: ""
        val project = env("SYSTEM_TEAMPROJECT") ?: ""
        val buildId = env("BUILD_BUILDID") ?: ""
        return CiContext(
            ciName = "azure-pipelines",
            branch = env("BUILD_SOURCEBRANCHNAME"),
            revision = env("BUILD_SOURCEVERSION"),
            remoteUrl = env("BUILD_REPOSITORY_URI"),
            jobUrl = "${server}${project}/_build/results?buildId=${buildId}",
        )
    }

    private fun detectBitBucket(env: (String) -> String?): CiContext? {
        env("BITBUCKET_PIPELINE_UUID") ?: return null
        val repoFullName = env("BITBUCKET_REPO_FULL_NAME") ?: ""
        val buildNumber = env("BITBUCKET_BUILD_NUMBER") ?: ""
        return CiContext(
            ciName = "bitbucket",
            branch = env("BITBUCKET_BRANCH"),
            revision = env("BITBUCKET_COMMIT"),
            remoteUrl = "https://bitbucket.org/$repoFullName",
            jobUrl = "https://bitbucket.org/$repoFullName/pipelines/results/$buildNumber",
        )
    }

    private fun detectSpace(env: (String) -> String?): CiContext? {
        env("JB_SPACE_API_URL") ?: return null
        val apiUrl = env("JB_SPACE_API_URL") ?: ""
        val projectKey = env("JB_SPACE_PROJECT_KEY") ?: ""
        val repoName = env("JB_SPACE_GIT_REPOSITORY_NAME") ?: ""
        return CiContext(
            ciName = "space",
            branch = env("JB_SPACE_GIT_BRANCH"),
            revision = env("JB_SPACE_GIT_REVISION"),
            remoteUrl = "ssh://git@git.${apiUrl}/${projectKey}/${repoName}.git",
            jobUrl = env("JB_SPACE_EXECUTION_URL"),
        )
    }

    private fun detectTeamCity(env: (String) -> String?): CiContext? {
        env("TEAMCITY_VERSION") ?: return null
        return CiContext(
            ciName = "teamcity",
            branch = env("BUILD_VCS_NUMBER_1"),
            revision = env("BUILD_VCS_NUMBER"),
        )
    }

    private fun detectCircleCI(env: (String) -> String?): CiContext? {
        if (env("CIRCLECI") != "true") return null
        return CiContext(
            ciName = "circleci",
            branch = env("CIRCLE_BRANCH"),
            revision = env("CIRCLE_SHA1"),
            remoteUrl = env("CIRCLE_REPOSITORY_URL"),
            jobUrl = env("CIRCLE_BUILD_URL"),
        )
    }
}

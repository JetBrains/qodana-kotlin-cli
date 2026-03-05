package org.jetbrains.qodana.engine.env

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

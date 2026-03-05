package org.jetbrains.qodana.engine.env

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CiDetectorTest {

    @Test
    fun `detect GitHub Actions`() {
        val env = mapOf(
            "GITHUB_ACTIONS" to "true",
            "GITHUB_REF_NAME" to "main",
            "GITHUB_SHA" to "abc123",
            "GITHUB_SERVER_URL" to "https://github.com",
            "GITHUB_REPOSITORY" to "JetBrains/qodana-cli",
            "GITHUB_RUN_ID" to "12345",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("github-actions", ci.ciName)
        assertEquals("main", ci.branch)
        assertEquals("abc123", ci.revision)
        assertEquals("https://github.com/JetBrains/qodana-cli", ci.remoteUrl)
        assertEquals("https://github.com/JetBrains/qodana-cli/actions/runs/12345", ci.jobUrl)
    }

    @Test
    fun `detect GitLab CI`() {
        val env = mapOf(
            "GITLAB_CI" to "true",
            "CI_COMMIT_REF_NAME" to "feature-branch",
            "CI_COMMIT_SHA" to "def456",
            "CI_REPOSITORY_URL" to "https://gitlab.com/org/repo.git",
            "CI_JOB_URL" to "https://gitlab.com/org/repo/-/jobs/789",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("gitlab-ci", ci.ciName)
        assertEquals("feature-branch", ci.branch)
        assertEquals("def456", ci.revision)
        assertEquals("https://gitlab.com/org/repo.git", ci.remoteUrl)
        assertEquals("https://gitlab.com/org/repo/-/jobs/789", ci.jobUrl)
    }

    @Test
    fun `no CI environment returns null`() {
        val ci = CiDetector.detect { null }
        assertNull(ci)
    }

    @Test
    fun `detect BitBucket Pipelines`() {
        val env = mapOf(
            "BITBUCKET_PIPELINE_UUID" to "{uuid}",
            "BITBUCKET_BRANCH" to "develop",
            "BITBUCKET_COMMIT" to "ghi789",
            "BITBUCKET_REPO_FULL_NAME" to "owner/repo",
            "BITBUCKET_BUILD_NUMBER" to "42",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("bitbucket", ci.ciName)
        assertEquals("develop", ci.branch)
        assertEquals("ghi789", ci.revision)
        assertEquals("https://bitbucket.org/owner/repo", ci.remoteUrl)
        assertEquals("https://bitbucket.org/owner/repo/pipelines/results/42", ci.jobUrl)
    }

    @Test
    fun `detect Jenkins`() {
        val env = mapOf(
            "JENKINS_URL" to "https://jenkins.example.com",
            "GIT_BRANCH" to "origin/main",
            "GIT_COMMIT" to "abc123",
            "GIT_URL" to "https://github.com/org/repo.git",
            "BUILD_URL" to "https://jenkins.example.com/job/test/1",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("jenkins", ci.ciName)
        assertEquals("origin/main", ci.branch)
        assertEquals("abc123", ci.revision)
        assertEquals("https://github.com/org/repo.git", ci.remoteUrl)
        assertEquals("https://jenkins.example.com/job/test/1", ci.jobUrl)
    }

    @Test
    fun `detect Azure Pipelines`() {
        val env = mapOf(
            "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://dev.azure.com/org/",
            "SYSTEM_TEAMPROJECT" to "myproject",
            "BUILD_BUILDID" to "999",
            "BUILD_SOURCEBRANCHNAME" to "main",
            "BUILD_SOURCEVERSION" to "sha999",
            "BUILD_REPOSITORY_URI" to "https://dev.azure.com/org/repo",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("azure-pipelines", ci.ciName)
        assertEquals("main", ci.branch)
        assertEquals("sha999", ci.revision)
        assertEquals("https://dev.azure.com/org/repo", ci.remoteUrl)
        assertEquals("https://dev.azure.com/org/myproject/_build/results?buildId=999", ci.jobUrl)
    }

    @Test
    fun `detect JetBrains Space`() {
        val env = mapOf(
            "JB_SPACE_API_URL" to "space.example.com",
            "JB_SPACE_PROJECT_KEY" to "PROJ",
            "JB_SPACE_GIT_REPOSITORY_NAME" to "myrepo",
            "JB_SPACE_GIT_BRANCH" to "refs/heads/main",
            "JB_SPACE_GIT_REVISION" to "rev123",
            "JB_SPACE_EXECUTION_URL" to "https://space.example.com/p/PROJ/jobs/123",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("space", ci.ciName)
        assertEquals("refs/heads/main", ci.branch)
        assertEquals("rev123", ci.revision)
        assertEquals("ssh://git@git.space.example.com/PROJ/myrepo.git", ci.remoteUrl)
        assertEquals("https://space.example.com/p/PROJ/jobs/123", ci.jobUrl)
    }

    @Test
    fun `detect TeamCity`() {
        val env = mapOf(
            "TEAMCITY_VERSION" to "2024.1",
            "BUILD_VCS_NUMBER_1" to "main",
            "BUILD_VCS_NUMBER" to "tc123",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("teamcity", ci.ciName)
        assertEquals("main", ci.branch)
        assertEquals("tc123", ci.revision)
    }

    @Test
    fun `detect CircleCI`() {
        val env = mapOf(
            "CIRCLECI" to "true",
            "CIRCLE_BRANCH" to "feature-x",
            "CIRCLE_SHA1" to "circle123",
            "CIRCLE_REPOSITORY_URL" to "https://github.com/org/repo",
            "CIRCLE_BUILD_URL" to "https://circleci.com/gh/org/repo/42",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("circleci", ci.ciName)
        assertEquals("feature-x", ci.branch)
        assertEquals("circle123", ci.revision)
        assertEquals("https://github.com/org/repo", ci.remoteUrl)
        assertEquals("https://circleci.com/gh/org/repo/42", ci.jobUrl)
    }

    @Test
    fun `GitHub Actions takes priority over GitLab`() {
        val env = mapOf(
            "GITHUB_ACTIONS" to "true",
            "GITHUB_REF_NAME" to "main",
            "GITLAB_CI" to "true",
            "CI_COMMIT_REF_NAME" to "gitlab-branch",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("github-actions", ci.ciName)
    }
}

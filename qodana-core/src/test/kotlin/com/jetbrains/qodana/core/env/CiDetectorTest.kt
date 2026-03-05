package com.jetbrains.qodana.core.env

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
    }

    @Test
    fun `detect GitLab CI`() {
        val env = mapOf(
            "GITLAB_CI" to "true",
            "CI_COMMIT_REF_NAME" to "feature-branch",
            "CI_COMMIT_SHA" to "def456",
        )
        val ci = CiDetector.detect { env[it] }
        assertNotNull(ci)
        assertEquals("gitlab-ci", ci.ciName)
        assertEquals("feature-branch", ci.branch)
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
        assertEquals("https://bitbucket.org/owner/repo/pipelines/results/42", ci.jobUrl)
    }
}

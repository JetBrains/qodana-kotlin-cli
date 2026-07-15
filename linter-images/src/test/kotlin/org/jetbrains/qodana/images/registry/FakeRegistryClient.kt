package org.jetbrains.qodana.images.registry

import org.jetbrains.qodana.images.RegistryTag

/**
 * Test double for [RegistryClient]. A `null` digest value means "no resolvable index" (the real 404 case);
 * a tag absent from the digest map is a test setup error. The `...OnSecondCall` variants model a concurrent
 * registry change between a repo's plan listing and its re-verify listing (retention re-lists once before
 * deleting): [tagsByRepoOnSecondCall] changes which tags exist, [digestsByRepoAndTagOnSecondCall] changes
 * where an existing tag points (a repush). [failingRepos] makes `listTags` throw, for per-repo isolation
 * tests. Every call is recorded so tests can assert exactly what was touched.
 */
class FakeRegistryClient(
    private val tagsByRepo: Map<String, List<RegistryTag>> = emptyMap(),
    private val tagsByRepoOnSecondCall: Map<String, List<RegistryTag>> = emptyMap(),
    private val digestsByRepoAndTag: Map<Pair<String, String>, String?> = emptyMap(),
    private val digestsByRepoAndTagOnSecondCall: Map<Pair<String, String>, String?> = emptyMap(),
    private val failingRepos: Set<String> = emptySet(),
) : RegistryClient {
    val listedRepos = mutableListOf<String>()
    val resolvedDigests = mutableListOf<Pair<String, String>>()
    val deletedDigests = mutableListOf<Pair<String, String>>()
    private val listCount = mutableMapOf<String, Int>() // repo -> how many times listTags has run

    override fun listTags(repo: String): List<RegistryTag> {
        if (repo in failingRepos) error("simulated failure for $repo")
        listedRepos += repo
        listCount[repo] = listCount.getOrDefault(repo, 0) + 1
        return if (listCount.getValue(repo) >= 2 && repo in tagsByRepoOnSecondCall) {
            tagsByRepoOnSecondCall.getValue(repo)
        } else {
            tagsByRepo[repo] ?: emptyList()
        }
    }

    override fun resolveDigest(
        repo: String,
        tag: String,
    ): String? {
        resolvedDigests += repo to tag
        // On/after the second listing, a repushed tag resolves to its new digest.
        if (listCount.getOrDefault(repo, 0) >= 2 && (repo to tag) in digestsByRepoAndTagOnSecondCall) {
            return digestsByRepoAndTagOnSecondCall[repo to tag]
        }
        check((repo to tag) in digestsByRepoAndTag) { "no fake digest configured for $repo:$tag" }
        return digestsByRepoAndTag[repo to tag]
    }

    override fun deleteByDigest(
        repo: String,
        digest: String,
    ) {
        deletedDigests += repo to digest
    }
}

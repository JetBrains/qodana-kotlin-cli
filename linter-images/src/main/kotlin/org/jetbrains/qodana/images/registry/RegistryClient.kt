package org.jetbrains.qodana.images.registry

import org.jetbrains.qodana.images.RegistryTag

/**
 * Port for an OCI-compliant registry — the seam `PruneRegistryCommand` tests against and
 * `SpaceRegistryClient` implements for `registry.jetbrains.team`. `repo` is a full docker-style
 * reference minus the tag, e.g. `"registry.jetbrains.team/p/sa/qodana-kcli-images/qodana-jvm"`.
 */
interface RegistryClient {
    /** Every tag in [repo], with its push time resolved (registries paginate; this drains all pages). */
    fun listTags(repo: String): List<RegistryTag>

    /** The digest of [tag]'s index manifest in [repo], or `null` if [tag] has no resolvable index. */
    fun resolveDigest(
        repo: String,
        tag: String,
    ): String?

    /** Deletes the manifest at [digest] in [repo] (Space rejects tag-deletes; digest only). */
    fun deleteByDigest(
        repo: String,
        digest: String,
    )
}

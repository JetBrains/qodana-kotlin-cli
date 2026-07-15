package org.jetbrains.qodana.images.registry

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import org.jetbrains.qodana.images.ResolvePublishMatrixCommand
import org.jetbrains.qodana.images.computeTagPrune
import java.time.Duration
import java.time.Instant

/**
 * Aggregates several failures into one, preserving the first as `cause` (so type/stack survive a
 * top-level rethrow / CI trace) and the rest via `addSuppressed`. Used at both levels — one repo's failed
 * deletes, and the whole run's failed repos — so the real underlying exception is never discarded.
 */
private class AggregateFailure(
    message: String,
    causes: List<Throwable>,
) : Exception(message, causes.firstOrNull()) {
    init {
        causes.drop(1).forEach { addSuppressed(it) }
    }
}

private data class DigestGroup(
    val digest: String,
    val allTags: List<String>,
    val pruneNames: Set<String>,
) {
    val safeToDelete: Boolean get() = allTags.all { it in pruneNames }
}

/**
 * Prunes stale tags from every image repo under `--registry`, per the pure `computeTagPrune` decision.
 * The repo set is single-sourced from [publishMatrix]. Every safe-to-delete group is re-verified against
 * one fresh full-repo listing immediately before any of them are deleted (bounded 2x-listing cost, not
 * scaling with the number of digests deleted), narrowing — not eliminating — the window against a
 * concurrent publish re-pointing a tag onto one of them. Isolation is two-tiered: one failed DELETE never
 * aborts the sibling deletes in the same repo, and one failed repo never aborts the others; each level
 * collects its failures and, only after attempting everything, throws an [AggregateFailure] that preserves
 * the real underlying exceptions as its cause/suppressed chain. (A listing/digest-resolution failure still
 * fails that whole repo — an incomplete tag→digest map can't prove a delete is collateral-free.)
 * `--dry-run` logs the plan and deletes nothing.
 */
class PruneRegistryCommand(
    private val client: () -> RegistryClient,
    private val publishMatrix: ResolvePublishMatrixCommand,
    private val now: () -> Instant = Instant::now,
) : CliktCommand(name = "prune-registry") {
    private val registry by option("--registry").required()
    private val dryRun by option("--dry-run").flag(default = false)
    private val keepNightly by option("--keep-nightly").int().default(7)
    private val snapshotMaxAgeDays by option("--snapshot-max-age-days").long().default(7)

    override fun run() {
        val c = client()
        val failures = mutableListOf<Pair<String, Throwable>>()
        for (image in repos()) {
            val repo = "$registry/$image"
            try {
                prune(c, repo)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // a cancellation signal, not a data failure — honor it
                throw e
            } catch (e: Exception) {
                echo("error: failed to prune $repo: ${e.message ?: e::class.simpleName}", err = true)
                failures += repo to e
            }
        }
        if (failures.isNotEmpty()) {
            throw AggregateFailure("failed to prune: ${failures.joinToString { it.first }}", failures.map { it.second })
        }
    }

    private fun repos(): List<String> = publishMatrix.rows().map { it.getValue("image") }.distinct().sorted()

    private fun prune(
        client: RegistryClient,
        repo: String,
    ) {
        val plan = digestGroups(client, repo)
        if (plan.isEmpty()) return
        val (safe, unsafe) = plan.partition { it.safeToDelete }
        unsafe.forEach { group ->
            val kept = group.allTags.filterNot { it in group.pruneNames }
            echo("skipping $repo@${group.digest}: shared with kept tag(s) ${kept.joinToString()}")
        }
        if (safe.isEmpty()) return

        // One fresh full-repo re-listing (not per-group) reflecting current state right before deleting.
        val recheck = digestGroups(client, repo).filter { it.safeToDelete }.associateBy { it.digest }
        val failures = mutableListOf<Pair<String, Throwable>>()
        for (group in safe) {
            if (recheck[group.digest]?.safeToDelete != true) {
                echo("skipping $repo@${group.digest}: no longer safe on re-check (concurrent publish?)")
                continue
            }
            try {
                val verb = if (dryRun) "[dry-run] would delete" else "deleting"
                echo("$verb $repo@${group.digest} (tags: ${group.allTags.joinToString()})")
                if (!dryRun) client.deleteByDigest(repo, group.digest)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                echo("error: failed to delete $repo@${group.digest}: ${e.message}", err = true)
                failures += group.digest to e
            }
        }
        if (failures.isNotEmpty()) {
            throw AggregateFailure("failed to delete in $repo: ${failures.joinToString { it.first }}", failures.map { it.second })
        }
    }

    private fun digestGroups(
        client: RegistryClient,
        repo: String,
    ): List<DigestGroup> {
        val tags = client.listTags(repo)
        val pruneNames = computeTagPrune(tags, now(), keepNightly, Duration.ofDays(snapshotMaxAgeDays)).toSet()
        if (pruneNames.isEmpty()) return emptyList()
        val digestOf = tags.mapNotNull { tag -> client.resolveDigest(repo, tag.name)?.let { tag.name to it } }.toMap()
        // A prune candidate matched computeTagPrune's grammar, so it IS one of our own nightly/snapshot tags,
        // which the build pipeline guarantees are proper indices. If its digest won't resolve, that's a
        // provable-invariant violation — fail the repo loudly (surfaced via run()'s aggregate + non-zero
        // exit) rather than a warning nobody reads in a green job. (A foreign tag with no index is fine — it
        // never appears in pruneNames.)
        val unresolvable = pruneNames.filter { it !in digestOf }
        check(unresolvable.isEmpty()) { "prune candidate(s) in $repo have no resolvable digest: ${unresolvable.joinToString()}" }
        val tagsByDigest = digestOf.entries.groupBy({ it.value }, { it.key })
        return pruneNames
            .map { digestOf.getValue(it) }
            .distinct()
            .map { digest -> DigestGroup(digest, tagsByDigest.getValue(digest), pruneNames) }
    }
}

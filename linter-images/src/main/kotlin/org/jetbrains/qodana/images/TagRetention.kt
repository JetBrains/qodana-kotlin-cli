package org.jetbrains.qodana.images

import java.time.Duration
import java.time.Instant

private const val DEFAULT_KEEP_NIGHTLY = 7
private const val SNAPSHOT_MAX_AGE_DAYS = 7L

// <mm>-<channel>[.<id>][-<runtime>] — id = <date[.counter]> (nightly) or <sha7> (snapshot); runtime = clangN / rubyX.Y.
private val TAG =
    Regex("""^(\d+\.\d+)-(nightly|snapshot)(?:\.([0-9a-f]+(?:\.\d+)?))?(?:-(?:clang\d+|ruby\d+\.\d+))?$""")

/** (date, counter) numeric key for a nightly id like "20260605" or "20260605.10" — numeric, so .10 > .2. */
private fun idKey(id: String): Pair<Long, Long> {
    val parts = id.split(".")
    return (parts[0].toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
}

/**
 * The tag names to prune from ONE repo, given its full tag listing:
 *  - nightly-exact (`<mm>-nightly.<date>[-runtime]`): keep the newest [keepNightly] DATE GENERATIONS
 *    (grouped so a date's bare + runtime variants live/die together; ordered numerically, not lexically),
 *    prune all tags of older generations;
 *  - snapshot (`<mm>-snapshot.<sha>[-runtime]`): prune any older than [snapshotMaxAge] by push time;
 *  - the moving `<mm>-nightly[-runtime]` pointer and any other tag (release/eap/rc/…) are NEVER pruned.
 * Pure: the caller resolves each returned name to its digest and deletes (dual tags share one digest).
 */
fun computeTagPrune(
    tags: List<RegistryTag>,
    now: Instant,
    keepNightly: Int = DEFAULT_KEEP_NIGHTLY,
    snapshotMaxAge: Duration = Duration.ofDays(SNAPSHOT_MAX_AGE_DAYS),
): List<String> {
    data class Parsed(
        val name: String,
        val channel: String,
        val id: String?,
        val generation: String,
    )

    val parsed =
        tags.mapNotNull { t ->
            val m = TAG.matchEntire(t.name) ?: return@mapNotNull null
            val mm = m.groupValues[1]
            val channel = m.groupValues[2]
            val id = m.groupValues[3].ifEmpty { null }
            Parsed(t.name, channel, id, "$mm-$channel" + if (id != null) ".$id" else "")
        }
    val byName = tags.associateBy { it.name }
    val prune = mutableListOf<String>()

    val nightlyExact = parsed.filter { it.channel == "nightly" && it.id != null }
    val keptGenerations =
        nightlyExact
            .map { it.id!! to it.generation }
            .distinctBy { it.second }
            .sortedWith(
                compareByDescending<Pair<String, String>> { idKey(it.first).first }
                    .thenByDescending { idKey(it.first).second },
            ).take(keepNightly)
            .map { it.second }
            .toSet()
    nightlyExact.filter { it.generation !in keptGenerations }.forEach { prune += it.name }

    // Snapshot: prune older than snapshotMaxAge.
    parsed
        .filter { it.channel == "snapshot" }
        .filter { Duration.between(byName.getValue(it.name).pushed, now) > snapshotMaxAge }
        .forEach { prune += it.name }

    return prune
}

package org.jetbrains.qodana.release

/**
 * Returns the tags whose assets should be stripped: every nightly release except the [keep] most-recent
 * by [NightlyRelease.publishedAt] (tie-broken by tag descending for determinism). Non-nightly tags (no
 * "-nightly" substring) are never returned, so stable releases are safe even if mistakenly passed in.
 */
fun selectNightlyTagsToStrip(
    releases: List<NightlyRelease>,
    keep: Int,
): List<String> {
    require(keep >= 0) { "keep must be >= 0, got $keep" }
    return releases
        .asSequence()
        .filter { "-nightly" in it.tag }
        .sortedWith(compareByDescending<NightlyRelease> { it.publishedAt }.thenByDescending { it.tag })
        .drop(keep)
        .map { it.tag }
        .toList()
}

/**
 * A published release as seen via the GitHub API.
 * [publishedAt] MUST be a fixed-width UTC ISO-8601 instant (e.g. "2026-06-02T00:00:00Z"), as GitHub's
 * `published_at` always is. Recency is compared by lexicographic string order, which equals chronological
 * order ONLY for that uniform format — a variable-width or non-UTC offset would sort incorrectly.
 */
data class NightlyRelease(
    val tag: String,
    val publishedAt: String,
)

package org.jetbrains.qodana.release

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd")

/** A source version is `<major>.<minor>` (2 segments) or `<major>.<minor>.<patch>` (3 segments). */
private const val MIN_VERSION_SEGMENTS = 2
private const val MAX_VERSION_SEGMENTS = 3

/**
 * The `<major>.<minor>` of a numeric `<major>.<minor>[.<patch>]` version (e.g. "2026.2" → "2026.2",
 * "2026.3.0"/"2026.3.1" → "2026.3"). The nightly stream is identified by the release line, not a patch —
 * there are no per-patch branches, so the patch is dropped.
 *
 * @throws IllegalArgumentException if [source] is not a numeric 2- or 3-segment `<major>.<minor>[.<patch>]`.
 */
fun nightlyBase(source: String): String {
    val segs = source.trim().split(".")
    require(
        segs.size in MIN_VERSION_SEGMENTS..MAX_VERSION_SEGMENTS &&
            segs.take(MIN_VERSION_SEGMENTS).all { it.toIntOrNull() != null },
    ) {
        "nightly base source '$source' is not a numeric <major>.<minor>[.<patch>]"
    }
    return "${segs[0]}.${segs[1]}"
}

/**
 * Computes `<base>-nightly.<yyyyMMdd>[.<counter>]`. `base` has no leading 'v' (e.g. "2026.2").
 * The counter is omitted for the day's first nightly; otherwise it is max(existing same-day counters)+1,
 * where a bare (counter-less) same-day tag counts as 0. max+1 (not the count) stays collision-free even
 * if an intermediate tag was deleted. Tags may carry an optional leading 'v'.
 */
fun computeNightlyVersion(
    base: String,
    today: LocalDate,
    existingNightlyTags: List<String>,
): String {
    val prefix = "$base-nightly.${today.format(YYYYMMDD)}"
    val re = Regex("^v?" + Regex.escape(prefix) + "(?:\\.([0-9]+))?$")
    val counters =
        existingNightlyTags.mapNotNull { tag ->
            val m = re.matchEntire(tag.trim()) ?: return@mapNotNull null
            m.groupValues[1].ifEmpty { "0" }.toIntOrNull() // toIntOrNull drops out-of-range counters
        }
    val next = counters.maxOrNull()?.plus(1)
    return if (next == null) prefix else "$prefix.$next"
}

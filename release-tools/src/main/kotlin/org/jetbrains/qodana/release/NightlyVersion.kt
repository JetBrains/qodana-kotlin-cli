package org.jetbrains.qodana.release

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * Computes `<base>-nightly.<yyyyMMdd>[.<counter>]`. `base` has no leading 'v' (e.g. "2026.2.1").
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

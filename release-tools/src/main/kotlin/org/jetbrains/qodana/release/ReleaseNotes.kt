package org.jetbrains.qodana.release

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A single changelog entry parsed from a commit subject. [type] is null for non-conventional subjects. */
data class Change(
    val type: String?,
    val scope: String?,
    val description: String,
    val rawSubject: String,
)

/** Changelog section. Declaration order is the render order; the heading carries the emoji. */
enum class Category(
    val heading: String,
) {
    FEATURES("🚀 Features"),
    FIXES("🐛 Bug Fixes"),
    PERFORMANCE("⚡️ Performance"),
    OTHER("🧹 Other changes"),
}

/** A parsed nightly tag `v?<base>-nightly.<yyyyMMdd>[.<counter>]`. */
data class NightlyTag(
    val base: String,
    val date: LocalDate,
    val counter: Int?,
)

// `(?:!)?` tolerates (and discards) the Conventional-Commit breaking-change marker; we don't surface it.
private val CONVENTIONAL = Regex("""^(?<type>[A-Za-z]+)(?:\((?<scope>[^)]+)\))?(?:!)?:\s*(?<desc>.*)$""")
private val NIGHTLY_TAG = Regex("""^v?(?<base>.+?)-nightly\.(?<date>\d{8})(?:\.(?<counter>\d+))?$""")
private val YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * Parses a commit subject as a Conventional Commit. A subject that doesn't match the
 * `type(scope)!?: desc` shape becomes a non-conventional [Change] (type null, raw subject as description).
 */
fun parseCommit(subject: String): Change {
    val s = subject.trim()
    val m =
        CONVENTIONAL.matchEntire(s)
            ?: return Change(type = null, scope = null, description = s, rawSubject = s)
    return Change(
        type = m.groups["type"]!!.value.lowercase(),
        scope = m.groups["scope"]?.value,
        description = m.groups["desc"]!!.value.trim(),
        rawSubject = s,
    )
}

/** Buckets a change: feat→Features, fix→Bug Fixes, perf→Performance, everything else→Other. */
fun categoryOf(change: Change): Category =
    when (change.type) {
        "feat" -> Category.FEATURES
        "fix" -> Category.FIXES
        "perf" -> Category.PERFORMANCE
        else -> Category.OTHER
    }

/** Renders one bullet line. Scope (when present) is bolded; non-conventional subjects render verbatim. */
fun renderChange(change: Change): String =
    when {
        change.type == null -> "- ${change.rawSubject}"
        change.scope != null -> "- **${change.scope}**: ${change.description}"
        else -> "- ${change.description}"
    }

/** Renders changes grouped into `### <heading>` sections in [Category] declaration order; empty in → "". */
fun renderCategorized(changes: List<Change>): String {
    val byCategory = changes.groupBy { categoryOf(it) }
    return Category.entries
        .filter { !byCategory[it].isNullOrEmpty() }
        .joinToString("\n\n") { category ->
            val lines = byCategory.getValue(category).joinToString("\n") { renderChange(it) }
            "### ${category.heading}\n$lines"
        }
}

/**
 * Parses `v?<base>-nightly.<yyyyMMdd>[.<counter>]`; null if the tag isn't a (dated) nightly tag, or if a
 * counter is present but out of Int range (mirrors `computeNightlyVersion`, which drops such tags).
 */
fun parseNightlyTag(tag: String): NightlyTag? {
    val m = NIGHTLY_TAG.matchEntire(tag.trim()) ?: return null
    val date = runCatching { LocalDate.parse(m.groups["date"]!!.value, YYYYMMDD) }.getOrNull()
    val counterText = m.groups["counter"]?.value
    val counter = counterText?.toIntOrNull()
    val counterValid = counterText == null || counter != null
    return if (date != null && counterValid) {
        NightlyTag(base = m.groups["base"]!!.value, date = date, counter = counter)
    } else {
        null
    }
}

/**
 * Human title for a nightly: `Qodana <base> Nightly (<yyyy-MM-dd>[ #<n>])`. The day's first build (no
 * counter) gets no `#`; counter `k` renders as build number `k + 1`.
 */
fun nightlyTitle(tag: NightlyTag): String {
    val suffix = tag.counter?.let { " #${it + 1}" } ?: ""
    return "Qodana ${tag.base} Nightly (${tag.date.format(DateTimeFormatter.ISO_LOCAL_DATE)}$suffix)"
}

/**
 * The previous nightly tag of the same release cycle (same [NightlyTag.base]) as [excludeTag], newest by
 * (date, counter), or null if none. A counter-less tag sorts as 0; ties break by tag string for determinism.
 */
fun selectPreviousNightlyTag(
    tags: List<String>,
    base: String,
    excludeTag: String,
): String? {
    val exclude = excludeTag.trim()
    return tags.asSequence()
        .map { it.trim() }
        .filter { it != exclude }
        .mapNotNull { raw -> parseNightlyTag(raw)?.let { raw to it } }
        .filter { (_, parsed) -> parsed.base == base }
        .maxWithOrNull(compareBy({ (_, p) -> p.date }, { (_, p) -> p.counter ?: 0 }, { (raw, _) -> raw }))
        ?.first
}

/**
 * The "Full changelog" compare-link footer line, or null when there is no stable baseline / repo / SHA.
 * The URL pins the head side to the commit [headSha] (not the not-yet-created tag) so it resolves while the
 * draft is unpublished; display text uses version strings (leading `v` stripped).
 */
fun compareLinkFooter(
    repo: String?,
    stableTag: String?,
    headSha: String?,
    currentTag: String,
): String? {
    if (repo == null || stableTag == null || headSha == null) return null
    val from = stableTag.removePrefix("v")
    val to = currentTag.removePrefix("v")
    return "**Full changelog**: [$from...$to](https://github.com/$repo/compare/$stableTag...$headSha)"
}

/**
 * Stitches the final release-notes markdown: an optional visible heading + categorized [visible] changes
 * (or [visibleEmptyNote] when they render empty), an optional `<details>` block of [collapsible] changes
 * (omitted when empty), and an optional [footer]. Output is trimmed with a single trailing newline.
 */
fun assembleNotes(
    visibleHeading: String?,
    visible: List<Change>,
    visibleEmptyNote: String,
    collapsibleSummary: String?,
    collapsible: List<Change>,
    footer: String?,
): String {
    val sb = StringBuilder()
    if (visibleHeading != null) sb.append(visibleHeading).append("\n\n")
    sb.append(renderCategorized(visible).ifBlank { visibleEmptyNote })
    if (collapsibleSummary != null && collapsible.isNotEmpty()) {
        sb.append("\n\n<details>\n<summary>").append(collapsibleSummary).append("</summary>\n\n")
        sb.append(renderCategorized(collapsible)).append("\n</details>")
    }
    if (footer != null) sb.append("\n\n---\n").append(footer)
    return sb.toString().trim() + "\n"
}

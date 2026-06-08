package org.jetbrains.qodana.release

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

// `(?:!)?` tolerates (and discards) the Conventional-Commit breaking-change marker; we don't surface it.
private val CONVENTIONAL = Regex("""^(?<type>[A-Za-z]+)(?:\((?<scope>[^)]+)\))?(?:!)?:\s*(?<desc>.*)$""")

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

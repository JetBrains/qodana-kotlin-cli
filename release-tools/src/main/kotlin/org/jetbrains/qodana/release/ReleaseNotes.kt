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

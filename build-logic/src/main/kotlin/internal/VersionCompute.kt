package internal

/**
 * Version-invariant state machine for the qodana-kotlin-cli release pipeline.
 *
 * The source of truth is `gradle.properties`'s `version=` line. At any commit, the source must be one of:
 *  - the literal `"dev"` (development state),
 *  - the same numeric version as the last stable tag (just-released),
 *  - exactly one semantic bump ahead of the last stable tag (release-prep).
 *
 * Versions are CalVer-ish: 2 or 3 dot-separated non-negative integer segments, no leading zeros,
 * no suffixes (`-rc1` etc.). Omitted patch implies `.0`.
 *
 * Bumps that the rule accepts (`prev = a.b.c`):
 *  - `a.b.(c+1)`  — patch bump
 *  - `a.(b+1).0` — minor bump
 *  - `(a+1).0.0` — major bump
 *
 * No mid-segment skips: from `2026.3.1`, `2026.3.3` is rejected (skips `.2`); from `2026.3.0`, `2026.5` is
 * rejected (skips `.4`); from `2026.3.0`, `2028.0.0` is rejected (skips `2027.0.0`).
 *
 * Nightly tags (`v…-nightly`) and probe tags (`v…-tagprobe-…`) must be filtered out by the caller before
 * being passed as `lastStableTag`.
 */
internal fun computeVersionState(source: String, lastStableTag: String?): VersionState {
    // Whitespace check first — `trim` must NOT silently fix invalid input.
    if (source != source.trim()) return VersionState.Invalid("source has leading/trailing whitespace")
    if (source.isEmpty()) return VersionState.Invalid("source-of-truth is empty")
    if (source == "dev") return VersionState.Dev

    val sourceParsed = JbSemVer.parse(source)
        ?: return VersionState.Invalid(parseError(source))

    if (lastStableTag == null) {
        // First-ever release: any well-formed numeric source counts as JustReleased.
        return VersionState.JustReleased(nextBase = "v${sourceParsed.canonical().bumpPatch()}")
    }

    val lastParsed = JbSemVer.parse(lastStableTag.removePrefix("v"))
        ?: return VersionState.Invalid("lastStableTag '$lastStableTag' is not a parseable v<semver>")

    val sourceCanon = sourceParsed.canonical()
    val lastCanon = lastParsed.canonical()

    if (sourceCanon == lastCanon) {
        return VersionState.JustReleased(nextBase = "v${sourceCanon.bumpPatch()}")
    }

    val validBumps = lastCanon.validBumps()
    if (sourceCanon in validBumps) {
        return VersionState.BumpAhead(nextBase = "v$sourceCanon")
    }

    val candidates = validBumps.joinToString(", ") { "v$it" }
    return VersionState.Invalid(
        "source v$sourceCanon is not a valid bump from v$lastCanon " +
            "(expected one of: $candidates, or equal to v$lastCanon for JustReleased state)",
    )
}

private fun parseError(source: String): String {
    // Surface a specific reason when easy. Otherwise fall back to a generic message.
    if (source.contains('-')) {
        val suffix = source.substringAfter('-')
        return "source has suffix '-$suffix' (pre-release suffixes are not allowed)"
    }
    for (seg in source.split('.')) {
        if (seg.length > 1 && seg.startsWith('0')) {
            return "source has leading zero in segment '$seg'"
        }
    }
    return "source '$source' is not 'dev' or numeric.dot form (2 or 3 segments)"
}

internal sealed class VersionState {
    /** Source-of-truth is the literal "dev" — no release activity expected. */
    data object Dev : VersionState()

    /** Source-of-truth equals the last stable tag — we just shipped this version. */
    data class JustReleased(val nextBase: String) : VersionState()

    /** Source-of-truth is exactly one bump ahead of the last stable tag — release prep. */
    data class BumpAhead(val nextBase: String) : VersionState()

    /** Source-of-truth violates the invariant. */
    data class Invalid(val message: String) : VersionState()
}

/**
 * JetBrains-flavored semver. Three numeric segments stored as `[major, minor, patch]`; omitted patch
 * normalizes to 0. Deviates from the canonical semver.org spec in two ways:
 *   1. `major` is a CalVer-style year (`2026`, `2027`, …) — not a "breaking-change-counter".
 *   2. The patch segment may be omitted in the source (`2026.2` ⇔ `2026.2.0`).
 *
 * Pre-release suffixes (`-rc1`, `-nightly`, etc.) are NOT accepted in source-of-truth or in stable tag
 * names; they're a separate concept handled by the workflow layer (the `-nightly` suffix appended after
 * `nightlyVersion`'s output).
 *
 * The `Jb` prefix is deliberate: do not confuse this with `org.gradle.util.GradleVersion` or any
 * library-provided SemVer type.
 */
internal data class JbSemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<JbSemVer> {
    override fun toString(): String = "$major.$minor.$patch"

    override fun compareTo(other: JbSemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    fun bumpPatch(): JbSemVer = copy(patch = patch + 1)

    fun validBumps(): List<JbSemVer> = listOf(
        JbSemVer(major, minor, patch + 1), // patch bump
        JbSemVer(major, minor + 1, 0),     // minor bump
        JbSemVer(major + 1, 0, 0),         // major bump
    )

    /** Identity, but lets callers chain `.canonical()` defensively. */
    fun canonical(): JbSemVer = this

    companion object {
        /** Parses `"2026.3"` (→ `2026.3.0`) or `"2026.3.1"`. Returns null on any malformation. */
        fun parse(s: String): JbSemVer? {
            val parts = s.split('.')
            if (parts.size !in 2..3) return null
            val ints = parts.map { seg ->
                if (seg.isEmpty()) return null
                // Reject leading zeros (except segment "0" itself).
                if (seg.length > 1 && seg.startsWith('0')) return null
                // Reject anything non-numeric (catches suffix or other gunk).
                if (seg.any { !it.isDigit() }) return null
                seg.toIntOrNull() ?: return null
            }
            return when (ints.size) {
                2 -> JbSemVer(ints[0], ints[1], 0)
                3 -> JbSemVer(ints[0], ints[1], ints[2])
                else -> null
            }
        }
    }
}

package org.jetbrains.qodana.release

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
@Suppress("ReturnCount") // Guard-clause state machine: each early return maps to one invariant branch.
fun computeVersionState(
    source: String,
    lastStableTag: String?,
): VersionState {
    // Whitespace check first — `trim` must NOT silently fix invalid input.
    if (source != source.trim()) return VersionState.Invalid("source has leading/trailing whitespace")
    if (source.isEmpty()) return VersionState.Invalid("source-of-truth is empty")
    if (source == "dev") return VersionState.Dev

    val sourceParsed =
        JbSemVer.parse(source)
            ?: return VersionState.Invalid(parseError(source))

    if (lastStableTag == null) {
        // First-ever release: any well-formed numeric source counts as JustReleased.
        return VersionState.JustReleased(nextBase = "v${sourceParsed.canonical().bumpPatch()}")
    }

    // Enforce the `v` prefix on lastStableTag — callers should pass the tag verbatim ("v2026.3.0",
    // not "2026.3.0"). The contract is documented at the top of this file. Tolerating a missing
    // prefix would hide caller mistakes where the tag query returned something unexpected.
    if (!lastStableTag.startsWith("v")) {
        return VersionState.Invalid(
            "lastStableTag '$lastStableTag' must start with 'v' " +
                "(caller contract: pass the tag verbatim as produced by `git tag --list 'v*'`)",
        )
    }
    val lastParsed =
        JbSemVer.parse(lastStableTag.removePrefix("v"))
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

/** Strict stable-tag form: vX.Y or vX.Y.Z, no leading zeros, no suffixes. */
val STABLE_TAG_REGEX = Regex("""^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:\.(?:0|[1-9]\d*))?$""")

/** First stable tag from a newest-first list (e.g. `git tag --sort=-v:refname`); null if none. */
fun selectLastStableTag(tagsNewestFirst: List<String>): String? =
    tagsNewestFirst.asSequence().map { it.trim() }.firstOrNull { it.matches(STABLE_TAG_REGEX) }

@Suppress("ReturnCount") // Guard-clause classifier: each return surfaces a distinct parse-failure reason.
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

sealed class VersionState {
    /** Source-of-truth is the literal "dev" — no release activity expected. */
    data object Dev : VersionState()

    /** Source-of-truth equals the last stable tag — we just shipped this version. */
    data class JustReleased(
        val nextBase: String,
    ) : VersionState()

    /** Source-of-truth is exactly one bump ahead of the last stable tag — release prep. */
    data class BumpAhead(
        val nextBase: String,
    ) : VersionState()

    /** Source-of-truth violates the invariant. */
    data class Invalid(
        val message: String,
    ) : VersionState()
}

/**
 * JetBrains-flavored semver. Three numeric segments stored as `[major, minor, patch]`; omitted patch
 * normalizes to 0. Deviates from the canonical semver.org spec in two ways:
 *   1. `major` is a CalVer-style year (`2026`, `2027`, …) — not a "breaking-change-counter".
 *   2. The patch segment may be omitted in the source (`2026.2` ⇔ `2026.2.0`).
 *
 * Pre-release suffixes (`-rc1`, `-nightly`, etc.) are NOT accepted in source-of-truth or in stable tag
 * names; they're a separate concept handled by the workflow layer (the dated `-nightly.<yyyyMMdd>` suffix
 * is appended by `compute-nightly-version.main.kts`).
 *
 * The `Jb` prefix is deliberate: do not confuse this with `org.gradle.util.GradleVersion` or any
 * library-provided SemVer type.
 */
data class JbSemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<JbSemVer> {
    // JB tags omit a zero patch: 2026.2.0 -> "2026.2"; 2026.2.1 -> "2026.2.1".
    override fun toString(): String = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"

    override fun compareTo(other: JbSemVer) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    fun bumpPatch(): JbSemVer = copy(patch = patch + 1)

    fun validBumps(): List<JbSemVer> =
        listOf(
            JbSemVer(major, minor, patch + 1), // patch bump
            JbSemVer(major, minor + 1, 0), // minor bump
            JbSemVer(major + 1, 0, 0), // major bump
        )

    /** Identity, but lets callers chain `.canonical()` defensively. */
    fun canonical(): JbSemVer = this

    companion object {
        /** Minimum dot-separated segments in a source version (`major.minor`). */
        private const val MIN_SEGMENTS = 2

        /** Maximum dot-separated segments in a source version (`major.minor.patch`). */
        private const val MAX_SEGMENTS = 3

        /** Parses `"2026.3"` (→ `2026.3.0`) or `"2026.3.1"`. Returns null on any malformation. */
        @Suppress("ReturnCount") // Guard-clause validator: each early return rejects a distinct malformation.
        fun parse(s: String): JbSemVer? {
            val parts = s.split('.')
            if (parts.size !in MIN_SEGMENTS..MAX_SEGMENTS) return null
            val ints =
                parts.map { seg ->
                    if (seg.isEmpty()) return null
                    // Reject leading zeros (except segment "0" itself).
                    if (seg.length > 1 && seg.startsWith('0')) return null
                    // Reject anything non-numeric (catches suffix or other gunk).
                    if (seg.any { !it.isDigit() }) return null
                    seg.toIntOrNull() ?: return null
                }
            return when (ints.size) {
                MIN_SEGMENTS -> JbSemVer(ints[0], ints[1], 0)
                MAX_SEGMENTS -> JbSemVer(ints[0], ints[1], ints[2])
                else -> null
            }
        }
    }
}

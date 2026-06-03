package org.jetbrains.qodana.release

/**
 * Normalizes a release version to canonical `v<core>[-prerelease][+build]`.
 * - core: JB-CalVer (2-3 numeric segments, no leading zeros); a zero patch is omitted (reuses [JbSemVer]).
 * - prerelease/build: validated per semver — dot-separated identifiers of [0-9A-Za-z-]; numeric
 *   pre-release identifiers must not have a leading zero (build identifiers may).
 * Returns failure (precise message) on malformation; never throws.
 */
@Suppress("ReturnCount") // Guard-clause normalizer: each early return rejects a distinct malformation.
fun normalizeReleaseVersion(input: String): Result<String> {
    if (input != input.trim()) return fail("version has leading/trailing whitespace: '$input'")
    if (input.isEmpty()) return fail("version is empty")
    val body = input.removePrefix("v")
    if (body.isEmpty()) return fail("version '$input' has no core")

    val parts = SuffixedVersion.split(body)
    val core =
        JbSemVer.parse(parts.core)
            ?: return fail("invalid core version '${parts.core}' (expected major.minor[.patch], no leading zeros)")
    validateSuffixes(parts.prerelease, parts.build)?.let { return fail(it) }

    val sb = StringBuilder("v").append(core) // JbSemVer.toString() omits a zero patch
    parts.prerelease?.let { sb.append('-').append(it) }
    parts.build?.let { sb.append('+').append(it) }
    return Result.success(sb.toString())
}

private fun fail(msg: String): Result<String> = Result.failure(IllegalArgumentException(msg))

/** Validates the optional pre-release and build segments; returns an error message or null if both are valid. */
@Suppress("ReturnCount") // Guard-clause validator: one return per malformed segment, plus the all-valid null.
private fun validateSuffixes(
    prerelease: String?,
    build: String?,
): String? {
    prerelease?.let { pr ->
        identifierError(pr, allowLeadingZeroNumeric = false)?.let { return "invalid pre-release '$pr': $it" }
    }
    build?.let { b ->
        identifierError(b, allowLeadingZeroNumeric = true)?.let { return "invalid build metadata '$b': $it" }
    }
    return null
}

/** Validates a dot-separated semver identifier sequence; returns an error message or null. */
@Suppress("ReturnCount") // Guard-clause validator: each early return reports a distinct identifier defect.
private fun identifierError(s: String, allowLeadingZeroNumeric: Boolean): String? {
    if (s.isEmpty()) return "empty"
    for (id in s.split('.')) {
        if (id.isEmpty()) return "empty identifier"
        if (!id.all(::isLegalIdentifierChar)) {
            return "identifier '$id' has illegal characters (allowed: 0-9 A-Z a-z '-')"
        }
        if (!allowLeadingZeroNumeric && hasLeadingZeroNumeric(id)) {
            return "numeric identifier '$id' has a leading zero"
        }
    }
    return null
}

/** Semver identifier alphabet: ASCII digits, letters, and the hyphen. */
private fun isLegalIdentifierChar(c: Char): Boolean = c.isDigit() || c in 'a'..'z' || c in 'A'..'Z' || c == '-'

/** True for an all-numeric identifier of length >1 that starts with '0' (e.g. `01`). */
private fun hasLeadingZeroNumeric(id: String): Boolean = id.length > 1 && id.startsWith('0') && id.all { it.isDigit() }

/** The `[-prerelease][+build]` split of a version body (the `v` prefix already removed). */
private data class SuffixedVersion(
    val core: String,
    val prerelease: String?,
    val build: String?,
) {
    companion object {
        /** Splits on the first `+` (build) then the first `-` (pre-release), per semver precedence. */
        fun split(body: String): SuffixedVersion {
            val plus = body.indexOf('+')
            val build = if (plus >= 0) body.substring(plus + 1) else null
            val beforeBuild = if (plus >= 0) body.substring(0, plus) else body

            val dash = beforeBuild.indexOf('-')
            val prerelease = if (dash >= 0) beforeBuild.substring(dash + 1) else null
            val core = if (dash >= 0) beforeBuild.substring(0, dash) else beforeBuild
            return SuffixedVersion(core, prerelease, build)
        }
    }
}

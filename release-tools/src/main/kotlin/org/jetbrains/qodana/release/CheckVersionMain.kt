package org.jetbrains.qodana.release

import kotlin.system.exitProcess

/**
 * Pure decision for the requireExact gate; returns an error message, or null if acceptable.
 * Compares source and requireExact CANONICALLY (so 2026.4 == 2026.4.0). Tagged dispatches require
 * BumpAhead, or JustReleased only on a fresh repo (no prior stable tag).
 */
@Suppress("ReturnCount") // Guard-clause gate: each return maps to one outcome (mismatch / wrong-state / accept).
fun requireExactError(
    source: String,
    requireExact: String,
    state: VersionState,
    lastTag: String?,
): String? {
    val srcCanon = JbSemVer.parse(source)?.toString()
    val reqCanon = JbSemVer.parse(requireExact)?.toString()
    if (srcCanon == null || reqCanon == null || srcCanon != reqCanon) {
        return "requireExact mismatch: gradle.properties has version='$source' but expected '$requireExact'. " +
            "Bump gradle.properties (and commit) before dispatching a release."
    }
    val firstEver = state is VersionState.JustReleased && lastTag == null
    if (state !is VersionState.BumpAhead && !firstEver) {
        return "Tagged releases require state=BumpAhead (or JustReleased on a fresh repo with no prior " +
            "`v*` tag), got $state."
    }
    return null
}

/**
 * `:release-tools:checkVersion` entry point. Reads -Dqodana.source (rootProject.version) and optional
 * -Dqodana.requireExact; shells git for stable tags; validates the bump-rule invariant. Non-zero exit on violation.
 */
fun main() {
    val source = System.getProperty("qodana.source") ?: failExit("checkVersion: -Dqodana.source not provided")
    val requireExact = System.getProperty("qodana.requireExact")?.ifBlank { null }

    val lastStable = selectLastStableTag(gitStableTagsNewestFirst())
    val state = computeVersionState(source, lastStable)
    if (state is VersionState.Invalid) failExit("Version invariant violated: ${state.message}")

    if (requireExact != null) {
        requireExactError(source, requireExact, state, lastStable)?.let { failExit(it) }
    }
    println("checkVersion: $state")
}

private fun failExit(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(1)
}

private fun gitStableTagsNewestFirst(): List<String> {
    val proc =
        ProcessBuilder("git", "tag", "--list", "v*", "--merged", "HEAD", "--sort=-v:refname")
            .redirectErrorStream(true)
            .start()
    val out = proc.inputStream.bufferedReader().readText()
    if (proc.waitFor() != 0) failExit("checkVersion: `git tag --list 'v*'` failed:\n$out")
    return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

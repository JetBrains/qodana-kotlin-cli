@file:Import("../src/main/kotlin/org/jetbrains/qodana/release/ReleaseNotes.kt")
@file:Import("../src/main/kotlin/org/jetbrains/qodana/release/VersionCompute.kt")

import org.jetbrains.qodana.release.Change
import org.jetbrains.qodana.release.assembleNotes
import org.jetbrains.qodana.release.compareLinkFooter
import org.jetbrains.qodana.release.nightlyTitle
import org.jetbrains.qodana.release.parseCommit
import org.jetbrains.qodana.release.parseNightlyTag
import org.jetbrains.qodana.release.selectLastStableTag
import org.jetbrains.qodana.release.selectPreviousNightlyTag
import java.io.File
import kotlin.system.exitProcess

// --- args -----
var tag: String? = null
var mode = "tagged"
var repo: String? = null
var headSha: String? = null
var repoDir = "."
var i = 0
while (i < args.size) {
    when (val a = args[i]) {
        "--tag" -> tag = args.getOrNull(++i)
        "--mode" -> mode = args.getOrNull(++i) ?: mode
        "--repo" -> repo = args.getOrNull(++i)
        "--head-sha" -> headSha = args.getOrNull(++i)
        "--repo-dir" -> repoDir = args.getOrNull(++i) ?: repoDir
        else -> { System.err.println("unknown arg: $a"); exitProcess(2) }
    }
    i++
}
val currentTag = tag ?: run { System.err.println("--tag is required"); exitProcess(2) }
if (mode != "nightly" && mode != "tagged") {
    System.err.println("--mode must be 'nightly' or 'tagged', got '$mode'")
    exitProcess(2)
}

// stderr is merged into stdout for diagnostics on failure; the parsers below tolerate stray lines.
fun sh(vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (p.waitFor() != 0) {
        System.err.println("command failed: ${cmd.joinToString(" ")}\n$out")
        exitProcess(1)
    }
    return out
}

fun git(vararg gitArgs: String): List<String> =
    sh("git", "-C", repoDir, *gitArgs).lines().map { it.trim() }.filter { it.isNotEmpty() }

fun commits(range: String): List<Change> =
    git("log", "--no-merges", "--pretty=format:%s", range).map { parseCommit(it) }

// --- tags (scoped to commits reachable from HEAD) + footer -----
val stableTags = git("tag", "--list", "v*", "--merged", "HEAD", "--sort=-v:refname")
val latestStable = selectLastStableTag(stableTags) // e.g. "v2026.2", or null
val stableVer = latestStable?.removePrefix("v")
val footer = compareLinkFooter(repo = repo, stableTag = latestStable, headSha = headSha, currentTag = currentTag)

// All history up to HEAD when there is no stable baseline.
val sinceStable = latestStable?.let { "$it..HEAD" } ?: "HEAD"

fun singleSection(): String =
    assembleNotes(
        visibleHeading = null,
        visible = commits(sinceStable),
        visibleEmptyNote = "_No changes in this release._",
        collapsibleSummary = null,
        collapsible = emptyList(),
        footer = footer,
    )

// --- build notes + title -----
val (title, notes) = if (mode == "nightly") {
    val parsed = parseNightlyTag(currentTag)
    val t = parsed?.let { nightlyTitle(it) } ?: currentTag
    val prevNightly =
        parsed?.let { selectPreviousNightlyTag(git("tag", "--list", "*-nightly*", "--merged", "HEAD"), it.base, currentTag) }
    val n = when {
        // Full split: a stable baseline exists to delimit the collapsed "earlier changes".
        prevNightly != null && latestStable != null ->
            assembleNotes(
                visibleHeading = "## Since the last nightly",
                visible = commits("$prevNightly..HEAD"),
                visibleEmptyNote = "_No changes since the last nightly._",
                collapsibleSummary = "Earlier changes since $stableVer",
                collapsible = commits("$latestStable..$prevNightly"),
                footer = footer,
            )
        // Prior nightly but no stable yet: just the since-last-nightly delta (no collapsible/footer).
        prevNightly != null ->
            assembleNotes(
                visibleHeading = null,
                visible = commits("$prevNightly..HEAD"),
                visibleEmptyNote = "_No changes since the last nightly._",
                collapsibleSummary = null,
                collapsible = emptyList(),
                footer = footer,
            )
        // First nightly of the cycle: a single section since the last stable (or full history).
        else -> singleSection()
    }
    t to n
} else {
    currentTag to singleSection()
}

File("release-notes.md").writeText(notes)
File("release-title.txt").writeText(title + "\n")
println("--- release-title.txt ---")
println(title)
println("--- release-notes.md ---")
println(notes)
println("--- end ---")

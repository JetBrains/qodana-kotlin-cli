@file:Import("../src/main/kotlin/org/jetbrains/qodana/release/NightlyCleanup.kt")

import org.jetbrains.qodana.release.NightlyRelease
import org.jetbrains.qodana.release.selectNightlyTagsToStrip
import kotlin.system.exitProcess

var keep = 7
var dryRun = false
var i = 0
while (i < args.size) {
    when (val a = args[i]) {
        "--keep" -> keep = args.getOrNull(++i)?.toIntOrNull()
            ?: run { System.err.println("--keep needs an integer"); exitProcess(2) }
        "--dry-run" -> dryRun = true
        else -> { System.err.println("unknown arg: $a"); exitProcess(2) }
    }
    i++
}

fun sh(vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (p.waitFor() != 0) {
        System.err.println("command failed: ${cmd.joinToString(" ")}\n$out")
        exitProcess(1)
    }
    return out
}

// Enumerate ALL releases via the paginated API (no arbitrary --limit). Keep only published (non-draft)
// nightly releases; published_at gives unambiguous recency.
val jq = """.[] | select(.draft | not) | select(.tag_name | test("-nightly")) | [.tag_name, .published_at] | @tsv"""
val tsv = sh("gh", "api", "--paginate", "/repos/{owner}/{repo}/releases", "--jq", jq)
val releases = tsv.lines().mapNotNull { line ->
    val p = line.split('\t')
    if (p.size < 2) null else NightlyRelease(p[0].trim(), p[1].trim())
}

val toStrip = selectNightlyTagsToStrip(releases, keep)
println("nightly releases: ${releases.size}; keep $keep; stripping assets from ${toStrip.size}")
for (tag in toStrip) {
    val assets = sh("gh", "release", "view", tag, "--json", "assets", "--jq", ".assets[].name")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
    for (asset in assets) {
        if (dryRun) { println("[dry-run] would delete $tag / $asset"); continue }
        println("deleting $tag / $asset")
        sh("gh", "release", "delete-asset", tag, asset, "--yes")
    }
}

@file:Import("../src/main/kotlin/org/jetbrains/qodana/release/VersionCompute.kt")
@file:Import("../src/main/kotlin/org/jetbrains/qodana/release/NightlyVersion.kt")

import org.jetbrains.qodana.release.VersionState
import org.jetbrains.qodana.release.computeNightlyVersion
import org.jetbrains.qodana.release.computeVersionState
import org.jetbrains.qodana.release.selectLastStableTag
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

fun sh(vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (p.waitFor() != 0) {
        System.err.println("command failed: ${cmd.joinToString(" ")}\n$out")
        exitProcess(1)
    }
    return out
}

val source = File("gradle.properties").readLines()
    .firstOrNull { it.trim().startsWith("version=") }
    ?.substringAfter("version=")?.trim()
    ?: run { System.err.println("no version= in gradle.properties"); exitProcess(1) }

// Stable tags are scoped to ancestors of HEAD (--merged) for the bump rule.
val stable = sh("git", "tag", "--list", "v*", "--merged", "HEAD", "--sort=-v:refname")
    .lines().map { it.trim() }.filter { it.isNotEmpty() }
val base = when (val s = computeVersionState(source, selectLastStableTag(stable))) {
    is VersionState.JustReleased -> s.nextBase
    is VersionState.BumpAhead -> s.nextBase
    is VersionState.Dev -> { System.err.println("version=dev; bump gradle.properties to a numeric version"); exitProcess(1) }
    is VersionState.Invalid -> { System.err.println("version invariant violated: ${s.message}"); exitProcess(1) }
}.removePrefix("v")

// Nightly counter intentionally queries ALL nightly tags repo-wide (no --merged): tag names are
// globally unique, so a same-day nightly on any ref would still collide on `gh release create`.
val nightlyTags = sh("git", "tag", "--list", "*-nightly*").lines().map { it.trim() }.filter { it.isNotEmpty() }
println("NIGHTLY_VERSION=v" + computeNightlyVersion(base, LocalDate.now(ZoneOffset.UTC), nightlyTags))

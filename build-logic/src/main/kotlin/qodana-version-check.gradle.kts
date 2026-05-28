// Convention plugin that enforces the version invariant on the root project (QD-14721, Phase C).
//
// `checkVersion`: fails the build if `gradle.properties`'s `version` is not in a release-eligible state
// relative to the most recent stable `v*` tag. Hook this into pre-push so contributors can't accidentally
// commit a bumped version that skips a segment.
//
// `nightlyVersion`: prints `NIGHTLY_VERSION=v<base>` to stdout. The umbrella nightly workflow extracts the
// base via `grep '^NIGHTLY_VERSION='` and appends `-nightly` once.
//
// Both tasks share the same compute logic from `internal.computeVersionState` and the same tag query:
//   git tag --list 'v*' --merged HEAD --sort=-v:refname   (filtered by `stableTagRegex` below)
//
// `--merged HEAD` ignores tags reachable only via unrelated branches. The strict `stableTagRegex`
// only accepts `vX.Y` / `vX.Y.Z` form — so `-nightly`, `-tagprobe-`, `-rc1`, and any other suffixed
// tags are rejected automatically without a separate grep step.

import internal.VersionState
import internal.computeVersionState

abstract class CheckVersionTask : DefaultTask() {
    @get:Input
    abstract val source: Property<String>

    @get:Input
    abstract val lastStableTag: Property<String>

    @get:Input
    @get:Optional
    abstract val requireExact: Property<String>

    @TaskAction
    fun run() {
        val src = source.get()
        val tag = lastStableTag.get().ifBlank { null }
        val state = computeVersionState(src, tag)
        if (state is VersionState.Invalid) {
            throw GradleException("Version invariant violated: ${state.message}")
        }

        val exact = requireExact.orNull
        if (exact != null) {
            if (src != exact) {
                throw GradleException(
                    "requireExact mismatch: gradle.properties has version='$src' but expected '$exact'. " +
                        "Bump gradle.properties (and commit) before dispatching a release.",
                )
            }
            // For tagged dispatches: BumpAhead is the normal release-prep state. JustReleased is also
            // accepted ONLY when there are no prior stable tags (first-ever release), because
            // computeVersionState treats `lastTag=null` as a JustReleased state for any numeric source.
            // Without this carve-out, the first dispatch (Task 10 of the plan) would always fail.
            val firstEverRelease = state is VersionState.JustReleased && tag == null
            if (state !is VersionState.BumpAhead && !firstEverRelease) {
                throw GradleException(
                    "Tagged releases require state=BumpAhead (or JustReleased on a fresh repo with no " +
                        "prior `v*` tag), got $state. " +
                        "If state=JustReleased with a prior tag, the version is already shipped; bump " +
                        "gradle.properties further. If state=Dev, set gradle.properties version to a numeric.",
                )
            }
        }

        logger.lifecycle("checkVersion: $state")
    }
}

abstract class NightlyVersionTask : DefaultTask() {
    @get:Input
    abstract val source: Property<String>

    @get:Input
    abstract val lastStableTag: Property<String>

    @TaskAction
    fun run() {
        val src = source.get()
        val tag = lastStableTag.get().ifBlank { null }
        val nextBase = when (val state = computeVersionState(src, tag)) {
            is VersionState.Dev -> throw GradleException(
                "cannot generate nightly version while gradle.properties has version=dev. " +
                    "Bump gradle.properties to a numeric version (e.g. the next planned release) first.",
            )
            is VersionState.JustReleased -> state.nextBase
            is VersionState.BumpAhead -> state.nextBase
            is VersionState.Invalid -> throw GradleException("Version invariant violated: ${state.message}")
        }
        // Marker line for the workflow to grep. Use println, not logger, so it goes to stdout
        // unambiguously even with `--quiet`.
        println("NIGHTLY_VERSION=$nextBase")
    }
}

// Strict stable-tag regex: only `vX.Y` or `vX.Y.Z` with non-negative integers, no leading zeros
// (except segment "0" itself), no suffixes. Any tag with `-rc1`, `-nightly`, `-tagprobe-`, or other
// trailing modifiers is excluded. Kept as a single source of truth — workflows use the same regex
// shape in their changelog-range computation.
private val stableTagRegex = Regex("""^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:\.(?:0|[1-9]\d*))?$""")

fun lastStableTagOrEmpty(): String {
    val outputFile = java.io.File.createTempFile("qodana-tags", ".txt")
    outputFile.deleteOnExit()
    // `redirectErrorStream(true)` merges stderr into the output file. If we left stderr as a pipe,
    // a large stderr (rare but possible — e.g., git warning torrent) would fill the OS pipe buffer
    // and deadlock waitFor() forever. Merging is fine here: we only care about exit code + first
    // matching stdout line; stderr in the output file is filtered out by the regex anyway.
    val pb = ProcessBuilder("git", "tag", "--list", "v*", "--merged", "HEAD", "--sort=-v:refname")
        .directory(rootDir)
        .redirectOutput(outputFile)
        .redirectErrorStream(true)
    val proc = pb.start()
    val exitCode = proc.waitFor()
    if (exitCode != 0) {
        // Fail closed: git failure (not-a-repo, missing git, permission denied, etc.) is unexpected
        // and must not be silently treated as "no tags" — that would weaken the bump-rule check.
        throw GradleException(
            "qodana-version-check: `git tag --list 'v*'` exited with code $exitCode. " +
                "Run from a git checkout with a working git binary on PATH. " +
                "Output:\n${outputFile.readText()}",
        )
    }
    return outputFile.readLines()
        .map { it.trim() }
        .firstOrNull { it.matches(stableTagRegex) }
        .orEmpty()
}

tasks.register<CheckVersionTask>("checkVersion") {
    group = "verification"
    description = "Validate that gradle.properties version is in a release-eligible state."
    source.set(project.version.toString())
    lastStableTag.set(provider { lastStableTagOrEmpty() })
    project.findProperty("requireExact")?.toString()?.let { requireExact.set(it) }
    notCompatibleWithConfigurationCache("reads git tag state at execution time")
}

tasks.register<NightlyVersionTask>("nightlyVersion") {
    group = "verification"
    description = "Print NIGHTLY_VERSION=<v<base>> for the nightly workflow to consume."
    source.set(project.version.toString())
    lastStableTag.set(provider { lastStableTagOrEmpty() })
    notCompatibleWithConfigurationCache("reads git tag state at execution time")
}

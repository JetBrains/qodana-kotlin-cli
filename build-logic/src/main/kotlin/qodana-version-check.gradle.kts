// Convention plugin that enforces the version invariant on the root project (QD-14721, Phase C).
//
// `checkVersion`: fails the build if `gradle.properties`'s `version` is not in a release-eligible state
// relative to the most recent stable `v*` tag. Hook this into pre-push so contributors can't accidentally
// commit a bumped version that skips a segment.
//
// `nightlyVersion`: prints `NIGHTLY_VERSION=v<base>` to stdout. The umbrella nightly workflow extracts the
// base via `grep '^NIGHTLY_VERSION='` and appends `-nightly` once.
//
// Both tasks share the same compute logic from `internal.VersionCompute` and the same tag query:
//   git tag --list 'v*' --merged HEAD --sort=-v:refname | grep -vE -- '(-nightly|-tagprobe-)' | head -n 1
//
// `--merged HEAD` ignores tags reachable only via unrelated branches. The `-tagprobe-` filter guards
// against debris from the empirical-probe task (Task 1 of the plan).

import internal.VersionCompute
import internal.VersionState

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
        val state = VersionCompute.compute(src, tag)
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
            if (state !is VersionState.BumpAhead) {
                throw GradleException(
                    "Tagged releases require state=BumpAhead, got $state. " +
                        "If state=JustReleased, the version is already shipped; bump gradle.properties further. " +
                        "If state=Dev, set gradle.properties version to a numeric.",
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
        val nextBase = when (val state = VersionCompute.compute(src, tag)) {
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

fun lastStableTagOrEmpty(): String {
    val pb = ProcessBuilder("git", "tag", "--list", "v*", "--merged", "HEAD", "--sort=-v:refname")
        .directory(rootDir)
        .redirectErrorStream(false)
    val proc = pb.start()
    val out = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    if (proc.exitValue() != 0) {
        // No tags / git failure → treat as no prior tag. Don't fail here; compute handles null.
        return ""
    }
    return out.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { !it.contains("-nightly") && !it.contains("-tagprobe-") }
        .firstOrNull()
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

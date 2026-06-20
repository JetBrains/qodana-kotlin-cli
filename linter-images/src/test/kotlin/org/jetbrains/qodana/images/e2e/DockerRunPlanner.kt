package org.jetbrains.qodana.images.e2e

import java.nio.file.Path

/**
 * Pure builder of the `docker run` argv for one e2e case run. Mounts the case's
 * project/results/cache at the fixed container paths the image expects, applies
 * the [RunSpec] network/caps/securityOpt/env, then appends the uniform
 * `scan --results-dir /data/results` plus optional --fail-threshold and the
 * case's extraArgs. Unit-tested by asserting the exact list.
 */
object DockerRunPlanner {
    fun dockerArgs(
        imageTag: String,
        projectDir: Path,
        resultsDir: Path,
        cacheDir: Path,
        run: RunSpec,
        hostEnv: Map<String, String> = System.getenv(),
    ): List<String> =
        buildList {
            add("docker")
            add("run")
            add("--rm")
            add("--network")
            add(run.network)
            add("-v")
            add("$projectDir:/data/project")
            add("-v")
            add("$resultsDir:/data/results")
            add("-v")
            add("$cacheDir:/data/cache")
            for (cap in run.capAdd) {
                add("--cap-add")
                add(cap)
            }
            for (opt in run.securityOpt) {
                add("--security-opt")
                add(opt)
            }
            for ((key, value) in run.env) {
                add("-e")
                add("$key=$value")
            }
            // Validate the host-side secret exists, then let Docker resolve the actual value from the
            // parent process environment via `-e KEY` so the secret does not end up in argv.
            for (key in run.passEnv) {
                if (hostEnv[key].isNullOrBlank()) {
                    error("required host env '$key' is missing or blank")
                }
                add("-e")
                add(key)
            }
            add(imageTag)
            add("scan")
            add("--results-dir")
            add("/data/results")
            // Pin the scan's cache + report dirs to the world-writable bind mounts. Without these the
            // native CLI leaves cacheDir/reportDir at their $HOME/.cache/JetBrains/Qodana/<id>/...
            // defaults (ScanCommand.resolvePaths — the --results-dir override does NOT relocate them),
            // which is unwritable in some images (e.g. qodana-go) and fails host-prep with AccessDenied.
            // /data/cache is already mounted (see LinterE2eCaseRunner); report nests under /data/results.
            add("--cache-dir")
            add("/data/cache")
            add("--report-dir")
            add("/data/results/report")
            run.failThreshold?.let {
                add("--fail-threshold")
                add(it.toString())
            }
            addAll(run.extraArgs)
        }
}

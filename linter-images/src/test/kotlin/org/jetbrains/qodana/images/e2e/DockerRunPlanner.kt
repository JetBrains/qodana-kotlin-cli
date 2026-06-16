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
            add(imageTag)
            add("scan")
            add("--results-dir")
            add("/data/results")
            run.failThreshold?.let {
                add("--fail-threshold")
                add(it.toString())
            }
            addAll(run.extraArgs)
        }
}

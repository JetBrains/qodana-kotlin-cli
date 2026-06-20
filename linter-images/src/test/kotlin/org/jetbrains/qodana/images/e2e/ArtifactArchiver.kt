package org.jetbrains.qodana.images.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.isRegularFile

/**
 * Copies a finished e2e run's diagnostics — `qodana.sarif.json` and the whole `log/` tree (which
 * holds `idea.log`, where a scan's real failure cause usually lands) — out of the JUnit `@TempDir`
 * results dir into a stable, build-relative [destDir] that CI uploads with `actions/upload-artifact`.
 * Absent inputs are skipped silently: a scan that fails before writing a SARIF/log is a legitimate
 * state, not an archiver error.
 *
 * The container's raw console ([stdout]/[stderr]) is persisted too when supplied. An IDE bootstrap
 * crash fails BEFORE file logging is configured, so it writes no `idea.log` — its real cause lives
 * only on the console. The console goes into [destDir] (host-owned, created here) rather than
 * `resultsDir/log` (which the UID-1000 container may own with perms the CI runner can't write).
 */
object ArtifactArchiver {
    fun archive(
        resultsDir: Path,
        destDir: Path,
        stdout: String? = null,
        stderr: String? = null,
    ) {
        Files.createDirectories(destDir)

        val sarif = resultsDir.resolve("qodana.sarif.json")
        if (sarif.isRegularFile()) {
            sarif.copyTo(destDir.resolve("qodana.sarif.json"), overwrite = true)
        }

        val log = resultsDir.resolve("log")
        if (Files.isDirectory(log)) {
            log.toFile().copyRecursively(destDir.resolve("log").toFile(), overwrite = true)
        }

        if (stdout != null) Files.writeString(destDir.resolve("container-stdout.txt"), stdout)
        if (stderr != null) Files.writeString(destDir.resolve("container-stderr.txt"), stderr)
    }
}

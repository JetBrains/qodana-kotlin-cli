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
 */
object ArtifactArchiver {
    fun archive(
        resultsDir: Path,
        destDir: Path,
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
    }
}

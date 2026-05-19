package org.jetbrains.qodana.core.port

import org.jetbrains.qodana.core.model.BaselineResult
import java.nio.file.Path

interface SarifService {
    fun read(path: Path): Any

    fun write(
        path: Path,
        report: Any,
    )

    fun merge(
        reports: List<Path>,
        output: Path,
    )

    fun baselineCompare(
        report: Path,
        baseline: Path,
        includeAbsent: Boolean = false,
    ): BaselineResult

    fun normalizePaths(
        reportPath: Path,
        projectDir: Path,
    )
}

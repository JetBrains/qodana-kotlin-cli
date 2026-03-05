package org.jetbrains.qodana.core.model

import java.nio.file.Path

data class ScanPaths(
    val projectDir: Path,
    val resultsDir: Path,
    val cacheDir: Path,
    val reportDir: Path,
)

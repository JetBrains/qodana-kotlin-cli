package org.jetbrains.qodana.core.model

import java.nio.file.Path

data class ThirdPartyScanContext(
    val paths: ScanPaths,
    val yaml: QodanaYaml?,
    val linterDir: Path,
    val noBuild: Boolean = false,
    val configurationName: String? = null,
    val solutionPath: String? = null,
    val projectPath: String? = null,
)

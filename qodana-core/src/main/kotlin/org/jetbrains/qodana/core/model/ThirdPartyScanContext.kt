package org.jetbrains.qodana.core.model

import java.nio.file.Path

data class ThirdPartyScanContext(
    val paths: ScanPaths,
    val yaml: QodanaYaml?,
    val linterDir: Path,
    val logDir: Path,
    val noBuild: Boolean = false,
    val noStatistics: Boolean = false,
    val configurationName: String? = null,
    val platformName: String? = null,
    val solutionPath: String? = null,
    val projectPath: String? = null,
    val compileCommands: String? = null,
    val clangArgs: String = "",
    val properties: List<String> = emptyList(),
    val customTools: Map<String, Path> = emptyMap(),
)

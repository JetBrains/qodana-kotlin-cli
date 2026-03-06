package org.jetbrains.qodana.engine.model

import org.jetbrains.qodana.core.model.QodanaYaml
import org.jetbrains.qodana.core.model.ScanPaths

data class ScanContext(
    val paths: ScanPaths,
    val auth: AuthContext,
    val runtime: RuntimeContext,
    val ci: CiContext,
    val report: ReportOptions,
    val docker: DockerOptions,
    val linter: String? = null,
    val profile: ProfileSpec? = null,
    val yaml: QodanaYaml? = null,
    val scenario: RunScenario = RunScenario.Default,
    val nativeMode: Boolean = false,
    val analysisMode: AnalysisMode = AnalysisMode.from(nativeMode),
)

data class ProfileSpec(
    val name: String? = null,
    val path: String? = null,
)

enum class AnalysisMode {
    NATIVE,
    CONTAINER;

    companion object {
        fun from(nativeMode: Boolean): AnalysisMode = if (nativeMode) NATIVE else CONTAINER
    }
}

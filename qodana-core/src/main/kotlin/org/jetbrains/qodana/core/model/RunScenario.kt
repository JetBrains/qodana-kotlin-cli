package org.jetbrains.qodana.core.model

sealed interface RunScenario {
    data object Default : RunScenario
    data object FullHistory : RunScenario
    data class LocalChanges(val diffStart: String? = null, val diffEnd: String? = null) : RunScenario
    data class Scoped(val targetBranch: String) : RunScenario
    data class ReverseScoped(val targetBranch: String) : RunScenario
}

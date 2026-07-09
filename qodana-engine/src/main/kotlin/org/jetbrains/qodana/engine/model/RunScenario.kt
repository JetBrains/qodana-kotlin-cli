package org.jetbrains.qodana.engine.model

sealed interface RunScenario {
    data object Default : RunScenario
    data object FullHistory : RunScenario
    data class Scoped(val targetBranch: String) : RunScenario
    data class ReverseScoped(val targetBranch: String) : RunScenario
}

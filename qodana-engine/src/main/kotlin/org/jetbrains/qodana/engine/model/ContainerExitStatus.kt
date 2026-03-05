package org.jetbrains.qodana.engine.model

data class ContainerExitStatus(
    val exitCode: Int,
    val oomKilled: Boolean = false,
)

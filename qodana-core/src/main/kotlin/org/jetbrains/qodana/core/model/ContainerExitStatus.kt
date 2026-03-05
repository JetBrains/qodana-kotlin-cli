package org.jetbrains.qodana.core.model

data class ContainerExitStatus(
    val exitCode: Int,
    val oomKilled: Boolean = false,
)

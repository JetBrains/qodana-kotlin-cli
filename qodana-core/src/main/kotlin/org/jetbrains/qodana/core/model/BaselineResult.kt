package org.jetbrains.qodana.core.model

data class BaselineResult(
    val newCount: Int,
    val unchangedCount: Int,
    val absentCount: Int,
)

package org.jetbrains.qodana.engine.model

data class CiContext(
    val branch: String? = null,
    val revision: String? = null,
    val ciName: String? = null,
    val remoteUrl: String? = null,
    val jobUrl: String? = null,
)

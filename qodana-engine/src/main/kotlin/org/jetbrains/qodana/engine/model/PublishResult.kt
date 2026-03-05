package org.jetbrains.qodana.engine.model

data class PublishResult(
    val url: String,
    val reportId: String,
    val success: Boolean,
)

package org.jetbrains.qodana.core.model

data class PublishResult(
    val url: String,
    val reportId: String,
    val success: Boolean,
)

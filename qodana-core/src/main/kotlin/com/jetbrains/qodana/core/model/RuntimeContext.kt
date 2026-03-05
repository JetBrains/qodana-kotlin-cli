package com.jetbrains.qodana.core.model

import java.nio.file.Path
import java.time.Duration

data class RuntimeContext(
    val ideDir: Path? = null,
    val timeout: Duration = Duration.ofMinutes(60),
    val envVars: Map<String, String> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
    val failThreshold: Int? = null,
    val disableStatistics: Boolean = false,
    val forceFullHistory: Boolean = false,
)

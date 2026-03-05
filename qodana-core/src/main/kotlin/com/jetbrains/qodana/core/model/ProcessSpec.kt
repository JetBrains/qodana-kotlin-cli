package com.jetbrains.qodana.core.model

import java.nio.file.Path
import java.time.Duration

data class ProcessSpec(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workDir: Path? = null,
    val timeout: Duration? = null,
)

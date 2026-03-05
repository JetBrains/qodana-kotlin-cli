package org.jetbrains.qodana.engine.model

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
    val clearCache: Boolean = false,
    val analysisId: String? = null,
    val jvmDebugPort: Int? = null,
    val applyFixes: Boolean = false,
    val cleanup: Boolean = false,
    val fixesStrategy: String? = null,
    val bootstrap: String? = null,
    val script: String = "default",
    val diffStart: String? = null,
    val diffEnd: String? = null,
    val onlyDirectory: String? = null,
    val coverageDir: Path? = null,
    val globalConfigDir: Path? = null,
)

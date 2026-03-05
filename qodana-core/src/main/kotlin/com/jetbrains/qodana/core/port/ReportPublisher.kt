package com.jetbrains.qodana.core.port

import com.jetbrains.qodana.core.model.PublishResult
import java.nio.file.Path

interface ReportPublisher {
    suspend fun publish(analysisId: String, reportPath: Path, token: String, endpoint: String): PublishResult
}

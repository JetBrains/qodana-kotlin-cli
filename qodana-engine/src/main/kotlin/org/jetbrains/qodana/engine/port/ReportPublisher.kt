package org.jetbrains.qodana.engine.port

import org.jetbrains.qodana.engine.model.PublishResult
import java.nio.file.Path

interface ReportPublisher {
    suspend fun publish(analysisId: String, reportPath: Path, token: String, endpoint: String): PublishResult
}

package org.jetbrains.qodana.engine.report

import org.jetbrains.qodana.engine.model.AuthContext
import org.jetbrains.qodana.engine.model.PublishResult
import org.jetbrains.qodana.engine.port.ReportPublisher
import java.nio.file.Path

class ReportPublishUseCase(
    private val reportPublisher: ReportPublisher,
) {
    suspend fun publish(
        analysisId: String,
        reportPath: Path,
        auth: AuthContext,
    ): Result<PublishResult> {
        val token = auth.token
            ?: return Result.failure(IllegalStateException("Cannot publish report: no Qodana Cloud token provided"))

        return try {
            val result = reportPublisher.publish(
                analysisId = analysisId,
                reportPath = reportPath,
                token = token,
                endpoint = auth.endpoint,
            )
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package org.jetbrains.qodana.app.report

import org.jetbrains.qodana.core.model.AuthContext
import org.jetbrains.qodana.core.model.PublishResult
import org.jetbrains.qodana.core.port.ReportPublisher
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

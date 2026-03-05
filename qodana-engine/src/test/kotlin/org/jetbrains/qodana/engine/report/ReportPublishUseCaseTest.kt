package org.jetbrains.qodana.engine.report

import kotlinx.coroutines.test.runTest
import org.jetbrains.qodana.engine.model.AuthContext
import org.jetbrains.qodana.engine.model.PublishResult
import org.jetbrains.qodana.engine.port.ReportPublisher
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportPublishUseCaseTest {

    @Test
    fun `publish fails when no token`() = runTest {
        val publisher = FakePublisher()
        val useCase = ReportPublishUseCase(publisher)

        val result = useCase.publish(
            analysisId = "a1",
            reportPath = Path.of("/results"),
            auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        )

        assertTrue(result.isFailure)
        assertEquals(0, publisher.callCount)
    }

    @Test
    fun `publish delegates to publisher with token`() = runTest {
        val publisher = FakePublisher()
        val useCase = ReportPublishUseCase(publisher)

        val result = useCase.publish(
            analysisId = "a1",
            reportPath = Path.of("/results"),
            auth = AuthContext(token = "test-token", endpoint = "https://qodana.cloud"),
        )

        assertTrue(result.isSuccess)
        assertEquals(1, publisher.callCount)
        assertEquals("test-token", publisher.lastToken)
        assertEquals("a1", publisher.lastAnalysisId)
    }

    @Test
    fun `publish wraps publisher exception as failure`() = runTest {
        val publisher = object : ReportPublisher {
            override suspend fun publish(analysisId: String, reportPath: Path, token: String, endpoint: String): PublishResult {
                throw RuntimeException("Network down")
            }
        }
        val useCase = ReportPublishUseCase(publisher)

        val result = useCase.publish(
            analysisId = "a1",
            reportPath = Path.of("/results"),
            auth = AuthContext(token = "token", endpoint = "https://qodana.cloud"),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network") == true)
    }
}

private class FakePublisher : ReportPublisher {
    var callCount = 0
    var lastToken: String? = null
    var lastAnalysisId: String? = null

    override suspend fun publish(analysisId: String, reportPath: Path, token: String, endpoint: String): PublishResult {
        callCount++
        lastToken = token
        lastAnalysisId = analysisId
        return PublishResult(url = "https://qodana.cloud/report/123", reportId = "123", success = true)
    }
}

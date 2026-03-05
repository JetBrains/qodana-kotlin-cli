package com.jetbrains.qodana.infra.publisher

import com.jetbrains.qodana.core.model.PublishResult as QodanaPublishResult
import com.jetbrains.qodana.core.port.ReportPublisher
import org.jetbrains.qodana.cloudclient.s3.QDCloudS3Client
import org.jetbrains.qodana.cloudclient.v1.QDCloudProjectApiV1
import org.jetbrains.qodana.publisher.Publisher
import org.jetbrains.qodana.publisher.PublisherParameters
import org.jetbrains.qodana.publisher.PublishResult
import org.jetbrains.qodana.publisher.schemas.PublisherReportType
import java.net.http.HttpClient
import java.nio.file.Path

class PublisherAdapter : ReportPublisher {

    override suspend fun publish(
        analysisId: String,
        reportPath: Path,
        token: String,
        endpoint: String,
    ): QodanaPublishResult {
        val projectApi = createProjectApi(token, endpoint)
        val s3Client = QDCloudS3Client(HttpClient.newHttpClient())
        val publisher = Publisher(projectApi, s3Client)

        val params = PublisherParameters(
            reportPath = reportPath,
            reportType = PublisherReportType.SARIF,
        )

        return when (val result = publisher.publish(params)) {
            is PublishResult.Success -> {
                val uploaded = result.uploadedReport
                QodanaPublishResult(
                    url = uploaded.reportLink,
                    reportId = uploaded.reportLink.removeSuffix("/").substringAfterLast("/"),
                    success = true,
                )
            }
            is PublishResult.Error -> {
                QodanaPublishResult(
                    url = "",
                    reportId = "",
                    success = false,
                )
            }
        }
    }

    private fun createProjectApi(token: String, endpoint: String): QDCloudProjectApiV1 {
        // TODO: Wire up QDCloudClient → v1() → project API using token + endpoint
        // This requires constructing QDCloudHttpClient with auth token and endpoint URL
        throw UnsupportedOperationException(
            "QDCloudProjectApiV1 construction needs to be wired through QDCloudClient. " +
            "See QodanaInspectionApplication.kt for reference."
        )
    }
}

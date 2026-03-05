package org.jetbrains.qodana.infra.publisher

import org.jetbrains.qodana.core.model.PublishResult as QodanaPublishResult
import org.jetbrains.qodana.core.port.ReportPublisher
import org.jetbrains.qodana.cloudclient.QDCloudClient
import org.jetbrains.qodana.cloudclient.QDCloudEnvironment
import org.jetbrains.qodana.cloudclient.QDCloudHttpClient
import org.jetbrains.qodana.cloudclient.QDCloudResponse
import org.jetbrains.qodana.cloudclient.s3.QDCloudS3Client
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
        val httpClient = QDCloudHttpClient(HttpClient.newHttpClient())
        val environment = QDCloudEnvironment(endpoint, httpClient)
        val client = QDCloudClient(httpClient, environment)

        val v1Response = client.v1()
        val clientV1 = when (v1Response) {
            is QDCloudResponse.Success -> v1Response.value
            else -> return QodanaPublishResult(url = "", reportId = "", success = false)
        }

        val projectApi = clientV1.projectApi(token)
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
}

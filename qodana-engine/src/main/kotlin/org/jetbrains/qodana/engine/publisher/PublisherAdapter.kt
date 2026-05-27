package org.jetbrains.qodana.engine.publisher

import org.jetbrains.qodana.cloudclient.QDCloudClient
import org.jetbrains.qodana.cloudclient.QDCloudEnvironment
import org.jetbrains.qodana.cloudclient.QDCloudHttpClient
import org.jetbrains.qodana.cloudclient.QDCloudResponse
import org.jetbrains.qodana.cloudclient.s3.QDCloudS3Client
import org.jetbrains.qodana.engine.port.ReportPublisher
import org.jetbrains.qodana.publisher.PublishResult
import org.jetbrains.qodana.publisher.Publisher
import org.jetbrains.qodana.publisher.PublisherParameters
import org.jetbrains.qodana.publisher.schemas.PublisherReportType
import java.net.http.HttpClient
import java.nio.file.Path
import org.jetbrains.qodana.engine.model.PublishResult as QodanaPublishResult

/**
 * Wraps the `qodana-publisher` library + QDCloudClient so the rest of the
 * engine can stay decoupled from those types.
 *
 * The HTTP clients are constructor-injected (QD-14728) so tests can substitute
 * `MockQDCloudHttpClient` and drive the full QDCloudClient + Publisher
 * Jackson-deserialization chain under the GraalVM tracing agent without
 * touching real cloud. The default `httpClient` and `s3Client` values are
 * constructed at PublisherAdapter instantiation (Kotlin evaluates default
 * argument expressions at object construction, not per `publish()` call) and
 * shared across every `publish()` invocation on this adapter instance.
 */
class PublisherAdapter(
    private val httpClient: QDCloudHttpClient = QDCloudHttpClient(HttpClient.newHttpClient()),
    private val s3Client: QDCloudS3Client = QDCloudS3Client(HttpClient.newHttpClient()),
) : ReportPublisher {
    override suspend fun publish(
        analysisId: String,
        reportPath: Path,
        token: String,
        endpoint: String,
    ): QodanaPublishResult {
        val environment = QDCloudEnvironment(endpoint, httpClient)
        val client = QDCloudClient(httpClient, environment)

        val v1Response = client.v1()
        val clientV1 =
            when (v1Response) {
                is QDCloudResponse.Success -> v1Response.value
                else -> return QodanaPublishResult(url = "", reportId = "", success = false)
            }

        val projectApi = clientV1.projectApi(token)
        val publisher = Publisher(projectApi, s3Client)

        val params =
            PublisherParameters(
                reportPath = reportPath,
                reportType = PublisherReportType.SARIF,
                analysisId = analysisId,
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

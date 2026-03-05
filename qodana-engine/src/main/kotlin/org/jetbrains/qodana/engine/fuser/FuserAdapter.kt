package org.jetbrains.qodana.engine.fuser

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.internal.statistic.eventLog.EventLogBuild
import com.intellij.internal.statistic.eventLog.validator.SensitiveDataValidator
import com.jetbrains.fus.reporting.client.CompositeMetadataStorage
import com.jetbrains.fus.reporting.model.config.v4.EventLogExternalSettings
import com.jetbrains.fus.reporting.model.lion3.LogEvent
import com.jetbrains.fus.reporting.model.lion3.LogEventAction
import com.jetbrains.fus.reporting.model.lion3.LogEventGroup
import com.jetbrains.fus.reporting.model.lion3.ValidatedFusRecord
import com.jetbrains.fus.reporting.model.lion3.ValidatedFusReport
import com.jetbrains.fus.reporting.model.metadata.EventGroupRemoteDescriptors
import org.jetbrains.qodana.engine.port.StatisticsReporter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.abs

class FuserAdapter(
    private val productCode: String,
    private val productVersion: String,
) : StatisticsReporter {

    private val recorderCode = "FUS"
    private val mapper = ObjectMapper()
    private val httpTimeout = Duration.ofSeconds(10)

    override suspend fun sendEvents(
        deviceId: String,
        productCode: String,
        events: List<Map<String, Any>>,
    ) {
        val configUrl = "https://resources.jetbrains.com/storage/fus/config/v4/$recorderCode/$productCode.json"

        val configJson = httpGet(configUrl)
        val config = mapper.readValue(configJson, EventLogExternalSettings::class.java).versions!!.first()

        val metadataJson = httpGet(config.getMetadataEndpoint(productCode)!!)
        val metadata = mapper.readValue(metadataJson, EventGroupRemoteDescriptors::class.java)

        val metadataStorage = CompositeMetadataStorage(
            metadata,
            buildParser = { build: String? -> EventLogBuild.fromString(build) },
            excludedFields = listOf("system_qdcld_project_id"),
        )
        val validator = SensitiveDataValidator(metadataStorage)

        val logEvents = events.map { event ->
            LogEvent(
                event["sessionId"] as? String ?: "",
                this.productVersion,
                bucket(deviceId).toString(),
                (event["time"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                LogEventGroup(event["groupId"] as? String ?: "", "1"),
                "75",
                LogEventAction(
                    event["eventName"] as? String ?: "",
                    event["state"] as? Boolean ?: false,
                    data = event.filterValues { it is String }.mapValues { it.value as String }.toMutableMap(),
                ),
            )
        }

        val report = ValidatedFusReport(
            productCode,
            deviceId,
            recorderCode,
            false,
            listOf(ValidatedFusRecord(logEvents)),
        )

        val validated = validator.validateReport(report) ?: return
        httpPost(config.getSendEndpoint(), validated)
    }

    private fun httpGet(url: String): String {
        val client = HttpClient.newBuilder()
            .connectTimeout(httpTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder()
            .timeout(httpTimeout)
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun httpPost(url: String?, report: ValidatedFusReport) {
        val entity = FuserSerializer.serialize(report)
        val client = HttpClient.newBuilder()
            .connectTimeout(httpTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder()
            .timeout(httpTimeout)
            .uri(URI.create(url!!))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(entity))
            .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun bucket(deviceId: String): Int = abs(deviceId.hashCode()) % 256
}

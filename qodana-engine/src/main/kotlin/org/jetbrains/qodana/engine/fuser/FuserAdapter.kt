package org.jetbrains.qodana.engine.fuser

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.internal.statistic.eventLog.EventLogBuild
import com.intellij.internal.statistic.eventLog.validator.SensitiveDataValidator
import com.jetbrains.fus.reporting.client.CompositeMetadataStorage
import com.jetbrains.fus.reporting.model.config.v4.Configuration
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
    private val productVersion: String,
) : StatisticsReporter {
    private val recorderCode = "FUS"

    // Parse the external, evolving FUS config/metadata with the repo's standard Kotlin-aware, lenient
    // mapper (cf. EffectiveConfig): ignore unknown fields so new config keys can't break resolution.
    private val mapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val httpTimeout = Duration.ofSeconds(10)

    override suspend fun sendEvents(
        deviceId: String,
        productCode: String,
        events: List<Map<String, Any>>,
    ) {
        val configUrl = "https://resources.jetbrains.com/storage/fus/config/v4/$recorderCode/$productCode.json"

        val endpoints = resolveEndpoints(httpGet(configUrl), productCode)

        val metadataJson = httpGet(endpoints.metadataUrl)
        val metadata = mapper.readValue(metadataJson, EventGroupRemoteDescriptors::class.java)

        val metadataStorage =
            CompositeMetadataStorage(
                metadata,
                buildParser = { build: String? -> EventLogBuild.fromString(build) },
                excludedFields = listOf("system_qdcld_project_id"),
            )
        val validator = SensitiveDataValidator(metadataStorage)

        val logEvents =
            events.map { event ->
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

        val report =
            ValidatedFusReport(
                productCode,
                deviceId,
                recorderCode,
                false,
                listOf(ValidatedFusRecord(logEvents)),
            )

        val validated = validator.validateReport(report) ?: return
        httpPost(endpoints.sendUrl, validated)
    }

    /** Resolves the FUS metadata + send URLs for this product/build from the downloaded config JSON. */
    internal fun resolveEndpoints(
        configJson: String,
        productCode: String,
    ): FusEndpoints {
        val config = mapper.readValue(configJson, Configuration::class.java)
        val version = config.findProductVersion(productVersion)
        // findProductVersion returns a non-null `empty` sentinel (no endpoints) when no build range
        // matches; these checks also cover a matched-but-incomplete version. Fail loudly instead of
        // building a "null<pc>.json" metadata URL or NPEing later on a null send URL.
        checkNotNull(version.provideMetadataEndpoint()) {
            "FUS config for build '$productVersion' (product '$productCode') has no metadata endpoint"
        }
        val sendUrl =
            checkNotNull(version.provideSendEndpoint()) {
                "FUS config for build '$productVersion' (product '$productCode') has no send endpoint"
            }
        return FusEndpoints(
            metadataUrl = version.provideMetadataProductUrl(productCode),
            sendUrl = sendUrl,
        )
    }

    private fun httpGet(url: String): String {
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(httpTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        val request =
            HttpRequest
                .newBuilder()
                .timeout(httpTimeout)
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun httpPost(
        url: String,
        report: ValidatedFusReport,
    ) {
        val entity = FuserSerializer.serialize(report)
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(httpTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        val request =
            HttpRequest
                .newBuilder()
                .timeout(httpTimeout)
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(entity))
                .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun bucket(deviceId: String): Int = abs(deviceId.hashCode()) % 256
}

internal data class FusEndpoints(
    val metadataUrl: String,
    val sendUrl: String,
)

package org.jetbrains.qodana.engine.port

interface StatisticsReporter {
    suspend fun sendEvents(
        deviceId: String,
        productCode: String,
        events: List<Map<String, Any>>,
    )
}
